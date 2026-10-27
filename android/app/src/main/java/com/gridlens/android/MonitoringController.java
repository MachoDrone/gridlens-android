package com.gridlens.android;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native foreground monitoring of explicitly approved, pinned endpoints. Never scans a network. */
final class MonitoringController {
    private final Activity activity;
    private final DiscoveryStore store;
    private final String origin, token;
    private final PublicNetworkCache networkCache;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(MonitoringPolicy.MAX_CONCURRENT_PCS);
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final Set<DiscoveryNetwork> networks = Collections.synchronizedSet(new HashSet<>());
    private final Map<DiscoveryNetwork, String> networkCredentials = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> blockedCredentialProfiles = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean foreground, destroyed;
    private volatile boolean storageBlocked;
    private volatile long observedProviderRetryAt;
    private volatile long generation;
    private volatile String selectedFleet;
    private String rewardsSource, gpuSource;
    private final Runnable tick = this::poll;

    MonitoringController(Activity activity, DiscoveryStore store, String origin, String token) {
        this.activity = activity; this.store = store; this.origin = origin; this.token = token;
        android.content.SharedPreferences observations = activity.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
        networkCache = new PublicNetworkCache(new PublicNetworkCache.Store() {
            public String read(String pcId) { return observations.getString(pcId, null); }
            public boolean write(String pcId, String value) { return observations.edit().putString(pcId, value).commit(); }
        }, System::currentTimeMillis);
    }
    static void validateTarget(JSONObject target) throws Exception {
        for (String key : new String[]{"id", "fleetId", "profileId"})
            if (!target.getString(key).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid monitoring identity");
        new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "");
        SshDiscovery.Identity identity = new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm"));
        String credential = target.getString("credentialId");
        if (!credential.isEmpty() && !credential.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid credential reference");
        if (!pcId(identity).equals(target.getString("pcId"))) throw new IllegalArgumentException("Invalid PC identity");
        JSONArray bindings = target.getJSONArray("bindings");
        if (bindings.length() > 64) throw new IllegalArgumentException("This PC exceeds 64 monitoring bindings; monitoring was not enabled for it");
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < bindings.length(); i++) {
            NodeBinding binding = binding(bindings.getJSONObject(i));
            if (!addresses.add(binding.address)) throw new IllegalArgumentException("Ambiguous node binding");
        }
        if (target.optString("sudoPassword", "").getBytes(StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("Invalid sudo credential");
    }
    private static NodeBinding binding(JSONObject value) throws Exception {
        return new NodeBinding(value.getString("runtime"), value.isNull("wrapperId") ? null : value.getString("wrapperId"),
                value.getString("nodeContainerId"), value.getString("address"));
    }
    private static JSONObject bindingJson(NodeBinding binding) throws Exception {
        return new JSONObject().put("runtime", binding.runtime).put("wrapperId", binding.wrapperId == null ? JSONObject.NULL : binding.wrapperId)
                .put("nodeContainerId", binding.nodeContainerId).put("address", binding.address);
    }
    static String pcId(SshDiscovery.Identity identity) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(identity.keyBase64));
        StringBuilder text = new StringBuilder();
        for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    /** Called only by the native discovery save flow after successful explicit approval. */
    List<JSONObject> register(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            Map<String, byte[]> sudoPasswords, boolean approve) throws Exception {
        JSONArray saved = store.monitoringTargets();
        List<JSONObject> setup = new ArrayList<>();
        for (SshDiscovery.BatchRequest request : requests) {
            SshDiscovery.Result result = results.get(request.candidate.endpointKey());
            if (result == null || result.bindings.isEmpty() || result.status == SshDiscovery.Status.AUTH_FAILED) continue;
            if (result.bindings.size() > 64) throw new IllegalArgumentException("This PC exceeds 64 monitoring bindings; monitoring was not enabled for it");
            JSONObject target = null;
            String verifiedPcId = pcId(request.identity);
            for (int i = 0; i < saved.length(); i++) {
                JSONObject old = saved.getJSONObject(i);
                if (fleetId.equals(old.getString("fleetId")) && verifiedPcId.equals(old.getString("pcId"))) {
                    if (target == null || request.candidate.address.equals(old.getString("address")) && request.candidate.port == old.getInt("port")) target = old;
                }
            }
            if (target != null && !approve && target.optBoolean("approved")) continue;
            boolean changedEndpoint = target != null && (!request.candidate.address.equals(target.getString("address")) || request.candidate.port != target.getInt("port"));
            JSONArray preservedSuspensions = changedEndpoint && target.optJSONArray("rewardsSuspended") != null
                    ? new JSONArray(target.getJSONArray("rewardsSuspended").toString()) : new JSONArray();
            JSONArray preservedVerifications = changedEndpoint && target.optJSONArray("rewardsVerified") != null
                    ? new JSONArray(target.getJSONArray("rewardsVerified").toString()) : new JSONArray();
            String credentialId = credentialIds.get(request.candidate.endpointKey());
            if (approve && credentialId == null) {
                SshDiscovery.Credential credential = request.credential;
                JSONObject nativeCredential = new JSONObject().put("name", "Monitoring " + request.candidate.address)
                        .put("username", credential.username).put("password", credential.password == null ? "" : new String(credential.password, StandardCharsets.UTF_8))
                        .put("key", credential.privateKey == null ? "" : android.util.Base64.encodeToString(credential.privateKey, android.util.Base64.NO_WRAP))
                        .put("passphrase", credential.passphrase == null ? "" : new String(credential.passphrase, StandardCharsets.UTF_8));
                credentialId = store.saveCredential(nativeCredential, request.identity.keyBase64);
            }
            if (target == null) target = new JSONObject().put("id", java.util.UUID.randomUUID().toString());
            JSONArray bindings = new JSONArray();
            for (NodeBinding binding : result.bindings) bindings.put(bindingJson(binding));
            Set<String> boundAddresses = new HashSet<>();
            for (NodeBinding binding : result.bindings) boundAddresses.add(binding.address);
            for (JSONArray retained : new JSONArray[]{preservedSuspensions, preservedVerifications})
                for (int i = retained.length() - 1; i >= 0; i--) if (!boundAddresses.contains(retained.getString(i))) retained.remove(i);
            if (!bindings.toString().equals(String.valueOf(target.optJSONArray("bindings")))) target.remove("gpuCursors");
            target.put("fleetId", fleetId).put("profileId", profileId).put("address", request.candidate.address).put("port", request.candidate.port)
                    .put("keyBase64", request.identity.keyBase64).put("algorithm", request.identity.algorithm).put("pcId", verifiedPcId)
                    .put("credentialId", credentialId == null ? "" : credentialId).put("bindings", bindings)
                    .put("approved", approve).put("paused", false).put("rewardsAuthorized", approve && preservedSuspensions.length() < bindings.length()).put("rewardsVerified", preservedVerifications)
                    .put("rewardsSuspended", preservedSuspensions).put("authorizationId", java.util.UUID.randomUUID().toString())
                    .put("setupPending", approve).put("lastSetupAttemptAtMs", 0).put("status", "authorization-required");
            if (sudoPasswords.containsKey(request.candidate.endpointKey())) {
                target.put("sudoApproved", true).put("sudoPassword", new String(sudoPasswords.get(request.candidate.endpointKey()), StandardCharsets.UTF_8));
            } else if (!target.has("sudoApproved")) target.put("sudoApproved", false);
            store.saveMonitoringTarget(target);
            if (approve) setup.add(new JSONObject(target.toString()));
        }
        return setup;
    }
    void startSetup(List<JSONObject> targets) {
        if (!targets.isEmpty()) storageBlocked = false; // A new explicit approval was successfully persisted.
        for (JSONObject target : targets) blockedCredentialProfiles.remove(target.optString("credentialId"));
        handler.removeCallbacks(tick);
        handler.post(tick);
    }
    void pauseFleet(String fleetId) throws Exception {
        cancelActive();
        JSONArray targets = store.monitoringTargets();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            if (!fleetId.equals(target.getString("fleetId"))) continue;
            target.put("approved", false).put("paused", true).put("status", "paused"); store.saveMonitoringTarget(target);
            workers.execute(() -> { try {
                Set<String> roster = currentRoster(fleetId);
                JSONArray bindings = target.getJSONArray("bindings");
                for (int j = 0; j < bindings.length(); j++) { NodeBinding binding = binding(bindings.getJSONObject(j));
                    if (roster.contains(binding.address)) postObservation(fleetId, observation(target, binding, "paused")); }
            } catch (Exception ignored) { } });
        }
    }
    interface RecoveryProgress { void changed(int completed, int total, int verified, int skipped); }
    static final class RecoveryReport {
        final int eligible, checked, verified, resumed, skipped;
        final boolean cancelled;
        final String failures;
        RecoveryReport(int eligible, int checked, int verified, int resumed, int skipped, boolean cancelled, String failures) {
            this.eligible = eligible; this.checked = checked; this.verified = verified; this.resumed = resumed; this.skipped = skipped; this.cancelled = cancelled;
            this.failures = failures;
        }
    }
    /** Explicit user retry only. Verifies existing SSH/node bindings without any source/helper read. */
    RecoveryReport checkSavedConnections(String fleetId, DiscoveryNetwork network, java.util.function.BooleanSupplier permitted,
            RecoveryProgress progress) throws Exception {
        if (storageBlocked) throw new IllegalStateException("Monitoring permission storage needs recovery");
        Set<String> roster = currentRoster(fleetId);
        List<JSONObject> targets = new ArrayList<>();
        JSONArray all = store.monitoringTargets();
        for (int i = 0; i < all.length(); i++) {
            JSONObject target = all.getJSONObject(i);
            if (!fleetId.equals(target.getString("fleetId")) || !MonitoringRecovery.eligible(target.optBoolean("approved"), target.optBoolean("paused"),
                    target.optString("status"), target.optBoolean("aliasReplaced"))) continue;
            validateTarget(target);
            if (!recoveryBindings(target, roster).isEmpty()) targets.add(target);
        }
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(), verified = new java.util.concurrent.atomic.AtomicInteger(), skipped = new java.util.concurrent.atomic.AtomicInteger();
        Object progressLock = new Object();
        progress.changed(0, targets.size(), 0, 0);
        List<MonitoringRecovery.Result<JSONObject>> results = MonitoringRecovery.check(targets, target -> target.optString("credentialId"), target -> {
            if (!permitted.getAsBoolean() || network.isCancelled()) return "unreachable";
            JSONObject credential;
            try { credential = store.credential(target.getString("credentialId")); }
            catch (Exception unavailable) { return "credentials-required"; }
            if (credential == null) return "credentials-required";
            DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "");
            if (!target.getString("keyBase64").equals(store.trustedKey(target.getString("profileId"), candidate.endpointKey()))) return "identity-changed";
            List<NodeBinding> bindings = recoveryBindings(target, roster);
            byte[] sudo = target.optBoolean("sudoApproved") ? target.optString("sudoPassword", "").getBytes(StandardCharsets.UTF_8) : null;
            try (SshDiscovery.Credential login = DiscoveryController.decodeCredential(credential)) {
                List<SshDiscovery.MonitoringResult> readings = SshDiscovery.monitor(network, candidate,
                        new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm")), login, sudo,
                        bindings, null, null, false, false, false, Collections.emptyMap());
                String outcome = "active";
                if (readings.size() != bindings.size()) return "binding-changed";
                Set<String> checked = new HashSet<>();
                for (SshDiscovery.MonitoringResult reading : readings) {
                    if (reading.rewardsJson != null || reading.gpuJson != null || reading.networkJson != null) return "unsupported";
                    boolean known = false;
                    for (NodeBinding expected : bindings) if (expected.address.equals(reading.binding.address) && expected.key().equals(reading.binding.key())) known = true;
                    if (!known || !checked.add(reading.binding.address)) return "binding-changed";
                    if ("credentials-required".equals(reading.status)) return "credentials-required";
                    if (!"active".equals(reading.status)) outcome = reading.status;
                }
                return outcome;
            } finally { if (sudo != null) Arrays.fill(sudo, (byte) 0); }
        }, () -> !permitted.getAsBoolean() || network.isCancelled(), result -> {
            try {
                if (result.attempted && "credentials-required".equals(result.status)) blockCredentialProfile(result.target);
                if (result.attempted && !"active".equals(result.status)) store.recordConnectionCheckFailure(result.target, result.status, System.currentTimeMillis());
                if (result.attempted && ("identity-changed".equals(result.status) || "binding-changed".equals(result.status)))
                    for (NodeBinding binding : recoveryBindings(result.target, roster)) {
                        try { postObservation(fleetId, observation(result.target, binding, result.status)); }
                        catch (Exception unavailable) { /* The native status still records the verified failure. */ }
                    }
                synchronized (progressLock) {
                    if (result.attempted && "active".equals(result.status)) verified.incrementAndGet();
                    if (!result.attempted) skipped.incrementAndGet();
                    progress.changed(completed.incrementAndGet(), targets.size(), verified.get(), skipped.get());
                }
            } catch (Exception failure) { storageBlocked = true; throw new IllegalStateException("The connection check could not save its result"); }
        }, network::cancel);
        int checked = 0;
        for (MonitoringRecovery.Result<JSONObject> result : results) if (result.attempted) checked++;
        java.util.Map<String, Integer> failures = new java.util.LinkedHashMap<>();
        for (MonitoringRecovery.Result<JSONObject> result : results) if (result.attempted && !"active".equals(result.status)) failures.merge(result.status, 1, Integer::sum);
        StringBuilder summary = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> failure : failures.entrySet()) summary.append(" ").append(failure.getValue()).append(" ").append(failure.getKey().replace('-', ' ')).append(".");
        if (!permitted.getAsBoolean() || network.isCancelled()) return new RecoveryReport(targets.size(), checked, verified.get(), 0, skipped.get(), true, summary.toString());
        JSONArray expected = new JSONArray(MonitoringRecovery.verified(results, target -> target.optString("credentialId")));
        JSONArray resumed = store.resumeVerifiedConnections(expected, () -> permitted.getAsBoolean() && !network.isCancelled());
        for (int i = 0; i < resumed.length(); i++) {
            JSONObject target = resumed.getJSONObject(i);
            blockedCredentialProfiles.remove(target.getString("credentialId"));
            for (NodeBinding binding : recoveryBindings(target, roster)) {
                try { postObservation(fleetId, observation(target, binding, "active")); }
                catch (Exception unavailable) { /* Next approved read will refresh the local display; source timestamps are untouched. */ }
            }
        }
        return new RecoveryReport(targets.size(), checked, verified.get(), resumed.length(), skipped.get(), false, summary.toString());
    }
    private static List<NodeBinding> recoveryBindings(JSONObject target, Set<String> roster) throws Exception {
        List<NodeBinding> bindings = new ArrayList<>(); JSONArray saved = target.getJSONArray("bindings");
        for (int i = 0; i < saved.length(); i++) { NodeBinding value = binding(saved.getJSONObject(i)); if (roster.contains(value.address)) bindings.add(value); }
        return bindings;
    }
    void selectedScenario(String scenario) {
        String next = scenario != null && scenario.matches("fleet:[a-f0-9-]{36}") ? scenario.substring(6) : null;
        if (!java.util.Objects.equals(next, selectedFleet)) { cancelActive(); selectedFleet = next; }
        if (foreground) { handler.removeCallbacks(tick); handler.post(tick); }
    }
    void resume() { foreground = true; handler.removeCallbacks(tick); handler.post(tick); }
    void pause() { foreground = false; cancelActive(); handler.removeCallbacks(tick); }
    void destroy() { destroyed = true; pause(); workers.shutdownNow(); }
    private synchronized void cancelActive() {
        generation++;
        synchronized (networks) { for (DiscoveryNetwork network : networks) network.cancel(); }
    }
    private void poll() {
        if (!foreground || destroyed || selectedFleet == null || storageBlocked) return;
        try {
            long now = System.currentTimeMillis();
            JSONArray targets = store.monitoringTargets();
            Map<String, JSONObject> eligible = new java.util.LinkedHashMap<>();
            Map<String, Long> oldest = new java.util.LinkedHashMap<>();
            Map<String, boolean[]> operations = new java.util.HashMap<>();
            for (int i = 0; i < targets.length(); i++) {
                JSONObject target = targets.getJSONObject(i); validateTarget(target);
                if (!selectedFleet.equals(target.getString("fleetId")) || inFlight.contains(target.getString("id"))
                        || blockedCredentialProfiles.contains(target.getString("credentialId"))) continue;
                boolean approved = target.optBoolean("approved"), paused = target.optBoolean("paused");
                boolean setup = target.optBoolean("setupPending") && MonitoringPolicy.due(true, approved, paused, now,
                        target.optLong("lastSetupAttemptAtMs"), target.optLong("retryAtMs"), 300_000);
                boolean gpu = MonitoringPolicy.due(true, approved, paused, now, target.optLong("lastGpuAttemptAtMs"), target.optLong("retryAtMs"), MonitoringPolicy.GPU_INTERVAL_MS);
                boolean rewards = target.optBoolean("rewardsAuthorized") && MonitoringPolicy.due(true, approved, paused, now,
                        target.optLong("lastRewardsAttemptAtMs"), Math.max(Math.max(observedProviderRetryAt, store.rewardsRetryAtMs()), Math.max(target.optLong("retryAtMs"), target.optLong("rewardsRetryAtMs"))), MonitoringPolicy.REWARDS_INTERVAL_MS);
                if (gpu || rewards || setup) {
                    String id = target.getString("id"); eligible.put(id, target); operations.put(id, new boolean[]{setup, rewards, gpu || setup});
                    long attempt = Math.min(gpu || setup ? target.optLong("lastGpuAttemptAtMs") : Long.MAX_VALUE,
                            rewards ? target.optLong("lastRewardsAttemptAtMs") : Long.MAX_VALUE);
                    oldest.put(id, attempt);
                }
            }
            for (String id : MonitoringPolicy.oldestFirst(oldest, MonitoringPolicy.MAX_CONCURRENT_PCS - inFlight.size())) {
                boolean[] actions = operations.get(id); dispatch(eligible.get(id), actions[0], actions[1], actions[2]);
            }
        } catch (Exception ignored) { /* Vault unavailable: no credentials or endpoints are guessed. */ }
        handler.postDelayed(tick, inFlight.isEmpty() ? 5000 : 1000);
    }
    private void dispatch(JSONObject target, boolean enable, boolean rewards, boolean gpu) throws Exception {
        String id = target.getString("id");
        if (!inFlight.add(id)) return;
        long now = System.currentTimeMillis();
        if (enable) target.put("lastSetupAttemptAtMs", now);
        if (rewards) target.put("lastRewardsAttemptAtMs", now);
        if (gpu) target.put("lastGpuAttemptAtMs", now);
        try { store.saveMonitoringTarget(target); }
        catch (Exception error) { inFlight.remove(id); throw error; }
        long run = generation;
        workers.execute(() -> {
            DiscoveryNetwork network = null;
            byte[] sudo = null;
            try {
                if (!activeTarget(run, target)) return;
                JSONObject credential;
                try { credential = store.credential(target.getString("credentialId")); }
                catch (Exception unavailable) { failure(target, "credentials-required", run); return; }
                if (credential == null) { failure(target, "credentials-required", run); return; }
                String trusted = store.trustedKey(target.getString("profileId"), new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "").endpointKey());
                if (!target.getString("keyBase64").equals(trusted)) { failure(target, "identity-changed", run); return; }
                Set<String> roster = currentRoster(target.getString("fleetId"));
                List<NodeBinding> bindings = new ArrayList<>();
                JSONArray stored = target.getJSONArray("bindings");
                for (int i = 0; i < stored.length(); i++) { NodeBinding binding = binding(stored.getJSONObject(i)); if (roster.contains(binding.address)) bindings.add(binding); }
                if (bindings.isEmpty()) return;
                if (!activeTarget(run, target)) return;
                try { network = DiscoveryNetwork.capture(activity); } catch (Exception unavailable) { failure(target, "network-unavailable", run); return; }
                networks.add(network);
                networkCredentials.put(network, target.getString("credentialId"));
                if (!activeTarget(run, target)) { network.cancel(); return; }
                if (target.optBoolean("sudoApproved")) sudo = target.optString("sudoPassword", "").getBytes(StandardCharsets.UTF_8);
                try (SshDiscovery.Credential selected = DiscoveryController.decodeCredential(credential)) {
                    Map<String, Long> cursors = new java.util.HashMap<>();
                    Set<String> verifiedRewards = new HashSet<>();
                    JSONArray verified = target.optJSONArray("rewardsVerified");
                    if (verified != null) for (int i = 0; i < verified.length(); i++) if (roster.contains(verified.getString(i))) verifiedRewards.add(verified.getString(i));
                    Set<String> suspendedRewards = new HashSet<>();
                    JSONArray suspended = target.optJSONArray("rewardsSuspended");
                    if (suspended != null) for (int i = 0; i < suspended.length(); i++) suspendedRewards.add(suspended.getString(i));
                    Set<String> rewardAddresses = new HashSet<>();
                    for (NodeBinding binding : bindings) if (!suspendedRewards.contains(binding.address)) rewardAddresses.add(binding.address);
                    JSONObject storedCursors = target.optJSONObject("gpuCursors");
                    if (storedCursors != null) for (NodeBinding binding : bindings) {
                        long cursor = storedCursors.optLong(binding.address, 0);
                        if (cursor > 0) cursors.put(binding.address, cursor);
                    }
                    Set<String> receivedRestrictions = new HashSet<>();
                    List<SshDiscovery.MonitoringResult> results = SshDiscovery.monitor(network,
                            new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), ""),
                            new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm")), selected, sudo,
                            bindings, source(true), source(false), enable, rewards, gpu, cursors, rewardAddresses,
                            result -> {
                                try {
                                    boolean rateLimited = result.rewardsJson != null && "rate-limited".equals(new JSONObject(result.rewardsJson).optString("status"));
                                    if (!rateLimited && !result.suspendRewards && result.retryAfterSeconds == 0 && !MonitoringPolicy.pausesTarget(result.status)) return;
                                    String receipt = result.binding.address + "|" + (result.rewardsJson == null ? result.status : result.rewardsJson);
                                    if (!receivedRestrictions.add(receipt)) return;
                                    long seconds = MonitoringPolicy.providerDelaySeconds(rateLimited, result.retryAfterSeconds);
                                    long deadline = seconds > 0 ? MonitoringPolicy.retryAt(System.currentTimeMillis(), seconds) : 0;
                                    noteProviderRetry(deadline);
                                    boolean stop = MonitoringPolicy.pausesTarget(result.status) && !(result.suspendRewards && "authorization-required".equals(result.status));
                                    if (MonitoringPolicy.suspendsCredentialProfile(result.status)) blockCredentialProfile(target);
                                    store.restrictMonitoring(target, result.binding.address, result.suspendRewards, stop, result.status, deadline);
                                    if (result.suspendRewards) { suspendedRewards.add(result.binding.address); verifiedRewards.remove(result.binding.address); }
                                    target.put("rewardsSuspended", new JSONArray(suspendedRewards)).put("rewardsVerified", new JSONArray(verifiedRewards));
                                    target.put("rewardsAuthorized", suspendedRewards.size() < bindings.size());
                                    if (stop) target.put("paused", true).put("status", result.status);
                                    if (deadline > 0) target.put("rewardsRetryAtMs", Math.max(target.optLong("rewardsRetryAtMs"), deadline));
                                } catch (Exception failure) { storageBlocked = true; throw new IllegalStateException("Monitoring restrictions could not be saved"); }
                            }, () -> activeTarget(run, target) && !storageBlocked && Math.max(observedProviderRetryAt, store.rewardsRetryAtMs()) <= System.currentTimeMillis(),
                            (binding, runner) -> networkCache.getOrRead(target.getString("pcId"), () -> {
                                if (!activeTarget(run, target)) throw new IllegalStateException("Monitoring cancelled");
                                return PublicIpReader.read(binding, target.getString("address"), target.optBoolean("sudoApproved"), runner, System::currentTimeMillis);
                            }));
                    if (!activeTarget(run, target)) return;
                    boolean paused = target.optBoolean("paused");
                    boolean setupComplete = enable;
                    String status = "active";
                    long retrySeconds = 0;
                    for (SshDiscovery.MonitoringResult result : results) {
                        if (!activeTarget(run, target)) return;
                        if ("active".equals(status) && !"active".equals(result.status)) status = result.status;
                        paused |= MonitoringPolicy.pausesTarget(result.status) && !(result.suspendRewards && "authorization-required".equals(result.status));
                        if (result.suspendRewards) { verifiedRewards.remove(result.binding.address); suspendedRewards.add(result.binding.address); }
                        retrySeconds = Math.max(retrySeconds, result.retryAfterSeconds);
                        // Denials and provider cooldowns survive a failed local delivery or process death.
                        // Never wait for the WebView/runtime to acknowledge revocation before stopping reads.
                        target.put("paused", paused).put("status", status).put("rewardsAuthorized", suspendedRewards.size() < bindings.size())
                                .put("rewardsVerified", new JSONArray(verifiedRewards)).put("rewardsSuspended", new JSONArray(suspendedRewards));
                        if (result.retryAfterSeconds > 0) target.put("rewardsRetryAtMs", Math.max(target.optLong("rewardsRetryAtMs"),
                                MonitoringPolicy.retryAt(System.currentTimeMillis(), result.retryAfterSeconds)));
                        commit(target, run);
                        JSONObject update = observation(target, result.binding, result.status);
                        JSONObject rewardReading = result.rewardsJson == null ? null : new JSONObject(result.rewardsJson);
                        JSONObject gpuReading = result.gpuJson == null ? null : new JSONObject(result.gpuJson);
                        if (enable && (gpuReading == null || java.util.Arrays.asList("unavailable", "setup-failed", "not-enabled").contains(gpuReading.optString("status")))) setupComplete = false;
                        if (rewardReading != null) update.put("rewards", rewardReading);
                        if (gpuReading != null) update.put("gpu", gpuReading);
                        if (result.networkJson != null) update.put("network", new JSONObject(result.networkJson));
                        postObservation(target.getString("fleetId"), update);
                        if (rewardReading != null && "ok".equals(rewardReading.optString("status"))) verifiedRewards.add(result.binding.address);
                        if (gpuReading != null) {
                            JSONArray samples = gpuReading.optJSONArray("samples"); long cursor = 0;
                            if (samples != null) for (int j = 0; j < samples.length(); j++) cursor = Math.max(cursor, samples.getJSONArray(j).getLong(0));
                            if (cursor > 0) {
                                if (storedCursors == null) storedCursors = new JSONObject();
                                storedCursors.put(result.binding.address, cursor); target.put("gpuCursors", storedCursors);
                            }
                        }
                        target.put("rewardsAuthorized", suspendedRewards.size() < bindings.size()).put("rewardsVerified", new JSONArray(verifiedRewards));
                        commit(target, run);
                    }
                    if (!activeTarget(run, target)) return;
                    target.put("paused", paused).put("status", status);
                    target.put("rewardsAuthorized", suspendedRewards.size() < bindings.size()).put("rewardsVerified", new JSONArray(verifiedRewards));
                    if (setupComplete) target.put("setupPending", false);
                    if (retrySeconds > 0) target.put("rewardsRetryAtMs", MonitoringPolicy.retryAt(System.currentTimeMillis(), retrySeconds));
                    if (!"active".equals(status)) target.put("retryAtMs", System.currentTimeMillis() + 60_000);
                    commit(target, run);
                }
            } catch (Exception error) {
                // SSH reachability/authentication have explicit transport results. A local schema,
                // vault or runtime failure is not evidence that the PC became unreachable.
                try { failure(target, "unsupported", run); } catch (Exception ignored) { }
            }
            finally {
                if (sudo != null) Arrays.fill(sudo, (byte) 0);
                if (network != null) { networkCredentials.remove(network); networks.remove(network); network.close(); }
                inFlight.remove(id);
            }
        });
    }
    private boolean active(long run) { return !destroyed && foreground && generation == run; }
    private boolean activeTarget(long run, JSONObject target) { return active(run) && !blockedCredentialProfiles.contains(target.optString("credentialId")); }
    private void blockCredentialProfile(JSONObject target) throws Exception {
        if (!store.suspendCredentialProfile(target)) return;
        String credential = target.getString("credentialId");
        boolean newlyBlocked = blockedCredentialProfiles.add(credential);
        for (Map.Entry<DiscoveryNetwork, String> connection : networkCredentials.entrySet())
            if (credential.equals(connection.getValue())) connection.getKey().cancel();
        if (newlyBlocked && !destroyed) try { workers.execute(() -> {
            try {
                String fleet = selectedFleet;
                if (fleet == null) return;
                Set<String> roster = currentRoster(fleet);
                JSONArray targets = store.monitoringTargets();
                for (int i = 0; i < targets.length(); i++) {
                    JSONObject current = targets.getJSONObject(i);
                    if (!fleet.equals(current.getString("fleetId")) || !credential.equals(current.getString("credentialId"))
                            || !current.optBoolean("paused") || !"credentials-required".equals(current.optString("status"))) continue;
                    JSONArray bindings = current.getJSONArray("bindings");
                    for (int j = 0; j < bindings.length(); j++) {
                        if (!blockedCredentialProfiles.contains(credential) || destroyed) return;
                        NodeBinding binding = binding(bindings.getJSONObject(j));
                        if (roster.contains(binding.address)) postObservation(fleet, observation(current, binding, "credentials-required"));
                    }
                }
            } catch (Exception ignored) { /* Native status stays authoritative if the local runtime is unavailable. */ }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private synchronized void noteProviderRetry(long deadline) { observedProviderRetryAt = Math.max(observedProviderRetryAt, deadline); }
    boolean storageNeedsRecovery() { return storageBlocked; }
    private synchronized void commit(JSONObject target, long run) throws Exception {
        if (activeTarget(run, target)) store.saveMonitoringTarget(target);
    }
    private synchronized String source(boolean rewards) throws Exception {
        String value = rewards ? rewardsSource : gpuSource;
        if (value == null) try (InputStream input = activity.getAssets().open(rewards ? "reward-reader/reader.cjs" : "gpu-collector/node_gpu.py")) {
            value = new String(NodeRuntime.readBounded(input, 128 * 1024), StandardCharsets.UTF_8);
            if (rewards) rewardsSource = value; else gpuSource = value;
        }
        return value;
    }
    private Set<String> currentRoster(String fleetId) throws Exception {
        JSONObject response = request("/mobile/fleets", null, 4 * 1024 * 1024);
        JSONArray fleets = response.getJSONArray("fleets");
        Set<String> addresses = new HashSet<>();
        for (int i = 0; i < fleets.length(); i++) {
            JSONObject fleet = fleets.getJSONObject(i);
            if (!fleetId.equals(fleet.getString("id"))) continue;
            JSONArray hosts = fleet.getJSONArray("hosts");
            for (int j = 0; j < hosts.length(); j++) addresses.add(hosts.getJSONObject(j).getString("address"));
        }
        return addresses;
    }
    private void failure(JSONObject target, String status, long run) throws Exception {
        if (!active(run)) return;
        if (MonitoringPolicy.suspendsCredentialProfile(status)) blockCredentialProfile(target);
        boolean alreadyPaused = target.optBoolean("paused");
        if (!alreadyPaused) target.put("status", status);
        target.put("paused", alreadyPaused || MonitoringPolicy.pausesTarget(status)).put("retryAtMs", System.currentTimeMillis() + 60_000);
        commit(target, run);
        Set<String> roster = currentRoster(target.getString("fleetId"));
        JSONArray bindings = target.getJSONArray("bindings");
        for (int i = 0; i < bindings.length(); i++) {
            NodeBinding binding = binding(bindings.getJSONObject(i));
            if (roster.contains(binding.address)) postObservation(target.getString("fleetId"), observation(target, binding, status));
        }
    }
    private JSONObject observation(JSONObject target, NodeBinding binding, String status) throws Exception {
        JSONObject identity = bindingJson(binding); identity.remove("address"); identity.put("pcId", target.getString("pcId"));
        return new JSONObject().put("address", binding.address).put("binding", identity)
                .put("monitoring", new JSONObject().put("status", status).put("checkedAtMs", System.currentTimeMillis()));
    }
    private void postObservation(String fleetId, JSONObject update) throws Exception {
        request("/mobile/observations", new JSONObject().put("schema", 1).put("fleetId", fleetId).put("updates", new JSONArray().put(update)), 64 * 1024);
    }
    private JSONObject request(String path, JSONObject body, int maximum) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
            connection.setRequestProperty("X-GridLens-Token", token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Observation exceeds bounds");
                connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Monitoring operation rejected");
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(NodeRuntime.readBounded(input, maximum), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
}
