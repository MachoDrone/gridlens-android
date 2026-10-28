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
    private final ExecutorService workers = Executors.newFixedThreadPool(MonitoringPolicy.NODE_SLOTS);
    private final ExecutorService ipWorker = Executors.newSingleThreadExecutor();
    private final Set<String> ipInFlight = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean reconcilingNetworkDelivery;
    private long reconciledNetworkGeneration = -1, lastNetworkReconciliationAt;

    private final PcNetworkReader fixtureNetworkReader;
    private final NodeOperation fixtureNodeOperation;
    interface PcNetworkReader { SshDiscovery.NetworkResult read(JSONObject target, SshDiscovery.Credential credential, java.util.function.BooleanSupplier permitted) throws Exception; }
    interface NodeOperation { void run(JSONObject target, boolean enable, boolean rewards, boolean gpu) throws Exception; }
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final Set<DiscoveryNetwork> networks = Collections.synchronizedSet(new HashSet<>());
    private final Map<DiscoveryNetwork, String> networkCredentials = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<DiscoveryNetwork, String> networkTargets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> blockedCredentialProfiles = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean foreground, destroyed;
    private volatile boolean storageBlocked;
    private volatile long observedProviderRetryAt;
    private volatile long generation;
    private volatile String selectedFleet;
    private String rewardsSource, gpuSource;
    private final Runnable tick = this::poll;

    MonitoringController(Activity activity, DiscoveryStore store, String origin, String token) {
        this(activity, store, origin, token, null, null);
    }
    /** Debug instrumentation seam; no web/native navigation can supply an implementation. */
    MonitoringController(Activity activity, DiscoveryStore store, String origin, String token, PcNetworkReader reader, NodeOperation nodeOperation) {
        if (!BuildConfig.DEBUG && (reader != null || nodeOperation != null)) throw new IllegalArgumentException("Fixture transport is debug-only");
        this.activity = activity; this.store = store; this.origin = origin; this.token = token;
        this.fixtureNetworkReader = reader; this.fixtureNodeOperation = nodeOperation;
        android.content.SharedPreferences observations = activity.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
        networkCache = new PublicNetworkCache(new PublicNetworkCache.Store() {
            public String read(String pcId) { return observations.getString(pcId, null); }
            public boolean write(String pcId, String value) { return observations.edit().putString(pcId, value).commit(); }
        }, System::currentTimeMillis);
    }
    PublicNetworkCache pcIpCache() { return networkCache; }
    void enqueueDirectPcIp(Runnable operation) { ipWorker.execute(operation); }
    void acceptDirectPcIpApproval(JSONObject target) {
        blockedCredentialProfiles.remove(target.optString("credentialId"));
    }
    void rejectDirectPcCredential(String credentialId) {
        blockedCredentialProfiles.add(credentialId);
        for (Map.Entry<DiscoveryNetwork, String> connection : networkCredentials.entrySet())
            if (credentialId.equals(connection.getValue())) connection.getKey().cancel();
    }
    void failDirectPcPermissionStorage() { storageBlocked = true; cancelActive(); }
    static void validateTarget(JSONObject target) throws Exception {
        validatePcTarget(target);
        validateNodeTarget(target);
    }
    /** PC IP authority is independent of node bindings and optional sudo state. */
    static void validatePcTarget(JSONObject target) throws Exception {
        for (String key : new String[]{"id", "fleetId", "profileId"})
            if (!target.getString(key).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid monitoring identity");
        new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "");
        SshDiscovery.Identity identity = new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm"));
        String credential = target.getString("credentialId");
        if (!credential.isEmpty() && !credential.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid credential reference");
        if (!pcId(identity).equals(target.getString("pcId"))) throw new IllegalArgumentException("Invalid PC identity");
    }
    private static void validateNodeTarget(JSONObject target) throws Exception {
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
    static final class PcIpEnrollmentReport {
        int enabled, unchanged;
        final List<String> excluded = new ArrayList<>();
        String summary() {
            return enabled + " PCs approved for automatic IP checks while this fleet is open. Rewards and GPU permissions were not added."
                    + (unchanged == 0 ? "" : " " + unchanged + " existing PC approvals were kept unchanged.")
                    + (excluded.isEmpty() ? "" : "\nAutomatic IP excluded: " + String.join("; ", excluded) + ".")
                    + " Keep this fleet open for its scheduled IP readings.";
        }
    }
    /** Native-only snapshots survive cancellation wiping the live SSH secret buffers. */
    static final class InspectionCredentialEvidence {
        private final Map<String, RememberedInspectionCredential> endpoints;
        private InspectionCredentialEvidence(Map<String, RememberedInspectionCredential> endpoints) {
            this.endpoints = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(endpoints));
        }
    }
    private static final class RememberedInspectionCredential {
        final String id, snapshot;
        RememberedInspectionCredential(String id, String snapshot) { this.id = id; this.snapshot = snapshot; }
    }
    InspectionCredentialEvidence captureInspectionCredentials(List<SshDiscovery.BatchRequest> requests,
            Map<String, String> credentialIds) throws Exception {
        Map<String, RememberedInspectionCredential> evidence = new java.util.LinkedHashMap<>();
        try {
            synchronized (store) {
                for (SshDiscovery.BatchRequest request : requests) {
                    String endpoint = request.candidate.endpointKey(), id = credentialIds.get(endpoint);
                    if (id == null) continue;
                    JSONObject remembered = store.credential(id);
                    if (remembered != null && matchesRememberedCredential(request, remembered))
                        evidence.put(endpoint, new RememberedInspectionCredential(id, remembered.toString()));
                }
            }
            return new InspectionCredentialEvidence(evidence);
        } catch (Exception failure) { storageBlocked = true; cancelActive(); throw failure; }
    }
    /** Persist real login rejection before host selection/save can be cancelled. No permission is granted here. */
    void recordInspectionFailure(InspectionCredentialEvidence evidence, String endpoint, SshDiscovery.Status status) throws Exception {
        if (status != SshDiscovery.Status.AUTH_FAILED) return;
        try {
            if (evidence == null) throw new IllegalArgumentException("Captured inspection credential evidence required");
            RememberedInspectionCredential rejected = evidence.endpoints.get(endpoint);
            if (rejected == null) return; // An ephemeral or changed credential cannot suspend a different saved profile.
            synchronized (store) {
                JSONObject current = store.credential(rejected.id);
                if (current == null || !rejected.snapshot.equals(current.toString())) return;
                store.suspendDirectPcCredential(rejected.id, rejected.snapshot);
                rejectDirectPcCredential(rejected.id);
            }
        } catch (Exception failure) { storageBlocked = true; cancelActive(); throw failure; }
    }
    /** Ordinary discovery grants only PC IP access, using credentials already remembered by the user. */
    PcIpEnrollmentReport registerAutomaticPcIp(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            java.util.function.BooleanSupplier consentActive) throws Exception {
        return registerAutomaticPcIp(fleetId, profileId, requests, results, credentialIds, consentActive, null);
    }
    PcIpEnrollmentReport registerAutomaticPcIp(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            java.util.function.BooleanSupplier consentActive, InspectionSelection selection) throws Exception {
        if (selection != null) selection.requireCompleteEvidence(results);
        PcIpEnrollmentReport report = new PcIpEnrollmentReport();
        Set<String> rejectedCredentials = rejectInspectedCredentials(requests, results, credentialIds);
        Set<String> associated = currentFleetPcIds(fleetId);
        Set<String> savedHosts = selection == null ? Collections.emptySet() : currentRoster(fleetId);
        for (SshDiscovery.BatchRequest request : requests) {
            String endpoint = request.candidate.endpointKey(), reason = null;
            String pc = pcId(request.identity);
            if (selection != null && !selection.includesEndpointPc(endpoint, pc)) {
                if (selection.resultFor(endpoint) != null) report.excluded.add(endpoint + " (selected hosts have no unambiguous PC association)");
                continue;
            }
            String credentialId = credentialIds.get(endpoint);
            SshDiscovery.Result result = results.get(endpoint);
            if (!consentActive.getAsBoolean()) reason = "approval interrupted";
            else if (storageBlocked) reason = "permission storage needs recovery";
            else if (!PcIpEnrollment.authenticatedPc(request, result)) reason = "PC identity or SSH login not verified";
            if (reason == null && !associated.contains(pc)) reason = "no matching saved host association";
            if (reason == null && selection != null && !savedHosts.containsAll(selection.resultFor(endpoint).addresses)) reason = "selected hosts are not all in the saved fleet";
            JSONObject remembered = credentialId == null ? null : store.credential(credentialId);
            if (reason == null && (remembered == null || !matchesRememberedCredential(request, remembered))) reason = "matching remembered SSH credentials required";
            if (reason == null && (rejectedCredentials.contains(credentialId) || !automaticCredentialAllowed(credentialId)
                    || selection != null && InspectionSelection.credentialWasRejected(request, requests, results))) reason = "saved SSH credentials remain paused";
            JSONObject existing = store.findMonitoringPc(fleetId, pc);
            if (reason == null && existing != null && !unusedInspection(existing)) {
                if (existing.optBoolean("approved") && !existing.optBoolean("aliasReplaced")
                        && MonitoringPolicy.pcNetworkPermitted(true, existing.optBoolean("paused"), existing.optString("status"))) {
                    report.unchanged++; continue;
                }
                reason = "existing restrictions kept; use the PC IP repair action";
            }
            if (reason == null && !automaticPcStateAllowed(fleetId, pc, existing, request.candidate)) reason = "saved PC or endpoint records need explicit repair";
            if (reason != null) { report.excluded.add(endpoint + " (" + reason + ")"); continue; }
            JSONObject target = existing == null ? new JSONObject().put("id", java.util.UUID.randomUUID().toString())
                    .put("bindings", new JSONArray()).put("rewardsAuthorized", false).put("rewardsVerified", new JSONArray())
                    .put("rewardsSuspended", new JSONArray()).put("sudoApproved", false)
                    .put("authorizationId", java.util.UUID.randomUUID().toString()) : new JSONObject(existing.toString());
            target.put("fleetId", fleetId).put("profileId", profileId).put("address", request.candidate.address).put("port", request.candidate.port)
                    .put("keyBase64", request.identity.keyBase64).put("algorithm", request.identity.algorithm).put("pcId", pc)
                    .put("credentialId", credentialId).put("approved", true).put("paused", true)
                    .put("setupPending", false).put("status", "binding-changed");
            boolean saved = store.approveDirectPcIp(existing, target, remembered.toString(), () -> consentActive.getAsBoolean()
                    && !storageBlocked && automaticCredentialAllowed(credentialId)
                    && automaticPcStateAllowed(fleetId, pc, existing, request.candidate));
            if (saved) report.enabled++;
            else report.excluded.add(endpoint + " (saved approval or credentials changed; no access added)");
        }
        return report;
    }
    /** Always inspect the entire batch before applying the user's narrower host choice. */
    private Set<String> rejectInspectedCredentials(List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds) throws Exception {
        Set<String> rejectedCredentials = new HashSet<>();
        for (SshDiscovery.BatchRequest request : requests) {
            SshDiscovery.Result result = results.get(request.candidate.endpointKey());
            String credentialId = credentialIds.get(request.candidate.endpointKey());
            if (result != null && result.status == SshDiscovery.Status.AUTH_FAILED && credentialId != null) {
                rejectedCredentials.add(credentialId);
                JSONObject remembered = store.credential(credentialId);
                if (remembered != null && matchesRememberedCredential(request, remembered)) {
                    try { store.suspendDirectPcCredential(credentialId, remembered.toString()); }
                    catch (Exception failure) { storageBlocked = true; cancelActive(); throw failure; }
                    rejectDirectPcCredential(credentialId);
                }
            }
        }
        return rejectedCredentials;
    }
    private boolean automaticCredentialAllowed(String credentialId) {
        if (credentialId == null || blockedCredentialProfiles.contains(credentialId)) return false;
        try {
            JSONArray saved = store.monitoringTargets();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject target = saved.optJSONObject(i);
                if (target != null && credentialId.equals(target.optString("credentialId"))
                        && "credentials-required".equals(target.optString("status"))) return false;
            }
            return true;
        } catch (Exception unavailable) { return false; }
    }
    private boolean automaticPcStateAllowed(String fleet, String pc, JSONObject expected, DiscoveryPolicy.Candidate endpoint) {
        try {
            int matches = 0;
            JSONArray saved = store.monitoringTargets();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject target = saved.optJSONObject(i);
                if (target == null || !fleet.equals(target.optString("fleetId"))) continue;
                if (pc.equals(target.optString("pcId"))) matches++;
                else if (endpoint.address.equals(target.optString("address")) && endpoint.port == target.optInt("port")) return false;
            }
            return matches == (expected == null ? 0 : 1);
        } catch (Exception unavailable) { return false; }
    }
    private static boolean matchesRememberedCredential(SshDiscovery.BatchRequest request, JSONObject remembered) throws Exception {
        try (SshDiscovery.Credential credential = DiscoveryController.decodeCredential(remembered)) {
            return PcIpEnrollment.sameCredential(request.credential, credential);
        }
    }
    private static boolean unusedInspection(JSONObject target) {
        boolean clear = Boolean.FALSE.equals(target.opt("approved")) && Boolean.FALSE.equals(target.opt("paused"))
                && Boolean.FALSE.equals(target.opt("setupPending")) && Boolean.FALSE.equals(target.opt("rewardsAuthorized"))
                && target.optJSONArray("rewardsVerified") != null && target.optJSONArray("rewardsVerified").length() == 0
                && target.optJSONArray("rewardsSuspended") != null && target.optJSONArray("rewardsSuspended").length() == 0
                && Boolean.FALSE.equals(target.opt("sudoApproved")) && !target.has("sudoPassword")
                && (!target.has("aliasReplaced") || Boolean.FALSE.equals(target.opt("aliasReplaced")))
                && target.opt("lastSetupAttemptAtMs") instanceof Number
                && ((Number) target.opt("lastSetupAttemptAtMs")).doubleValue() == 0;
        for (String field : new String[]{"gpuCursors", "lastGpuAttemptAtMs", "lastRewardsAttemptAtMs", "lastNetworkAttemptAtMs",
                "lastConnectionCheckAtMs", "lastConnectionCheckStatus", "retryAtMs", "rewardsRetryAtMs", "networkRetryAtMs", "recoveryGeneration"})
            if (target.has(field)) clear = false;
        if (target.optLong("lastSetupAttemptAtMs") != 0) clear = false;
        return PcIpEnrollment.unusedInspection(target.optBoolean("approved"), target.optBoolean("paused"), target.optString("status"),
                target.optBoolean("setupPending"), target.optBoolean("aliasReplaced"), clear);
    }
    static final class RegistrationDeferral {
        final String endpoint, reason;
        RegistrationDeferral(String endpoint, String reason) { this.endpoint = endpoint; this.reason = reason; }
    }
    static final class MonitoringRegistrationReport {
        final List<JSONObject> targets = new ArrayList<>();
        final List<RegistrationDeferral> deferred = new ArrayList<>();
        String summary() {
            return deferred.isEmpty() ? "" : deferred.size()
                    + " PCs need attention before additional monitoring can start. Open Enable / repair monitoring to review them.";
        }
        private void defer(SshDiscovery.BatchRequest request, String reason) {
            deferred.add(new RegistrationDeferral(request.candidate.endpointKey(), reason));
        }
    }
    /** Explicit repair keeps its existing PC scope; ordinary discovery passes a host selection. */
    List<JSONObject> register(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            Map<String, byte[]> sudoPasswords, boolean approve) throws Exception {
        return register(fleetId, profileId, requests, results, credentialIds, sudoPasswords, approve, null).targets;
    }
    MonitoringRegistrationReport register(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            Map<String, byte[]> sudoPasswords, boolean approve, InspectionSelection selection) throws Exception {
        return register(fleetId, profileId, requests, results, credentialIds, sudoPasswords, approve, selection, () -> true);
    }
    MonitoringRegistrationReport register(String fleetId, String profileId, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> results, Map<String, String> credentialIds,
            Map<String, byte[]> sudoPasswords, boolean approve, InspectionSelection selection,
            java.util.function.BooleanSupplier consentActive) throws Exception {
        if (consentActive == null) throw new IllegalArgumentException("Native consent state required");
        if (selection != null) selection.requireCompleteEvidence(results);
        Set<String> rejectedCredentials = selection == null ? Collections.emptySet()
                : rejectInspectedCredentials(requests, results, credentialIds);
        Set<String> associated = selection == null ? Collections.emptySet() : currentFleetPcIds(fleetId);
        Set<String> savedHosts = selection == null ? Collections.emptySet() : currentRoster(fleetId);
        JSONArray saved = store.monitoringTargets();
        MonitoringRegistrationReport report = new MonitoringRegistrationReport();
        for (SshDiscovery.BatchRequest request : requests) {
            if (!consentActive.getAsBoolean()) { report.defer(request, "Approval interrupted"); continue; }
            SshDiscovery.Result result = selection == null ? results.get(request.candidate.endpointKey())
                    : selection.resultFor(request.candidate.endpointKey());
            if (result == null || result.status == SshDiscovery.Status.AUTH_FAILED || result.status == SshDiscovery.Status.IDENTITY_CHANGED
                    || result.status == SshDiscovery.Status.CANCELLED || result.status == SshDiscovery.Status.UNREACHABLE) continue;
            boolean pcOnly = result.bindings.isEmpty();
            String verifiedPcId = pcId(request.identity);
            if (selection != null) {
                String credential = credentialIds.get(request.candidate.endpointKey());
                String reason = null;
                if (!selection.includesEndpointPc(request.candidate.endpointKey(), verifiedPcId)
                        || !PcIpEnrollment.authenticatedPc(request, result)) reason = "Selected hosts have no unambiguous verified PC association";
                else if (!associated.contains(verifiedPcId)) reason = "Selected hosts have no matching saved PC association";
                else if (!savedHosts.containsAll(result.addresses)) reason = "Selected hosts are not all in the saved fleet";
                else if (storageBlocked) reason = "Permission storage needs recovery";
                else if (InspectionSelection.credentialWasRejected(request, requests, results)
                        || rejectedCredentials.contains(credential)
                        || credential != null && !automaticCredentialAllowed(credential)) reason = "SSH credential rejection remains in effect";
                if (reason != null) { report.defer(request, reason); continue; }
            }
            if (pcOnly && (!approve || result.pcMetadata == null || !verifiedPcId.equals(result.pcMetadata.pcId)
                    || !currentFleetHasPc(fleetId, verifiedPcId))) continue;
            if (result.bindings.size() > 64) throw new IllegalArgumentException("This PC exceeds 64 monitoring bindings; monitoring was not enabled for it");
            JSONObject target = null;
            for (int i = 0; i < saved.length(); i++) {
                JSONObject old = saved.optJSONObject(i);
                if (old != null && fleetId.equals(old.optString("fleetId")) && verifiedPcId.equals(old.optString("pcId"))) {
                    if (target == null || request.candidate.address.equals(old.getString("address")) && request.candidate.port == old.getInt("port")) target = old;
                }
            }
            if (selection != null) {
                String reason = selectedRegistrationDeferral(saved, target, fleetId, verifiedPcId, request, result, selection);
                if (reason != null) { report.defer(request, reason); continue; }
            }
            JSONObject expectedTarget = selection == null || target == null ? null : new JSONObject(target.toString());
            if (target != null && !approve && target.optBoolean("approved")) continue;
            boolean changedEndpoint = target != null && (!request.candidate.address.equals(target.getString("address")) || request.candidate.port != target.getInt("port"));
            JSONArray preservedSuspensions = target != null && (selection != null || changedEndpoint || pcOnly) && target.optJSONArray("rewardsSuspended") != null
                    ? new JSONArray(target.getJSONArray("rewardsSuspended").toString()) : new JSONArray();
            JSONArray preservedVerifications = target != null && (selection != null || changedEndpoint || pcOnly) && target.optJSONArray("rewardsVerified") != null
                    ? new JSONArray(target.getJSONArray("rewardsVerified").toString()) : new JSONArray();
            String credentialId = credentialIds.get(request.candidate.endpointKey());
            if (approve && credentialId == null) {
                if (!consentActive.getAsBoolean()) { report.defer(request, "Approval interrupted"); continue; }
                SshDiscovery.Credential credential = request.credential;
                JSONObject nativeCredential = new JSONObject().put("name", "Monitoring " + request.candidate.address)
                        .put("username", credential.username).put("password", credential.password == null ? "" : new String(credential.password, StandardCharsets.UTF_8))
                        .put("key", credential.privateKey == null ? "" : android.util.Base64.encodeToString(credential.privateKey, android.util.Base64.NO_WRAP))
                        .put("passphrase", credential.passphrase == null ? "" : new String(credential.passphrase, StandardCharsets.UTF_8));
                synchronized (store) {
                    if (!consentActive.getAsBoolean()) { report.defer(request, "Approval interrupted"); continue; }
                    credentialId = store.saveCredential(nativeCredential, request.identity.keyBase64);
                }
            }
            if (selection != null && approve) {
                JSONObject remembered = credentialId == null ? null : store.credential(credentialId);
                if (remembered == null || !matchesRememberedCredential(request, remembered)) {
                    report.defer(request, "Saved SSH credentials changed after inspection"); continue;
                }
            }
            boolean previousRewardsAuthorized = target != null && target.optBoolean("rewardsAuthorized");
            String authorization = pcOnly && target != null ? target.optString("authorizationId", java.util.UUID.randomUUID().toString()) : java.util.UUID.randomUUID().toString();
            if (target == null) target = new JSONObject().put("id", java.util.UUID.randomUUID().toString());
            JSONArray bindings = pcOnly && target.optJSONArray("bindings") != null
                    ? new JSONArray(target.getJSONArray("bindings").toString()) : new JSONArray();
            if (!pcOnly) for (NodeBinding binding : result.bindings) bindings.put(bindingJson(binding));
            if (!pcOnly) {
                Set<String> boundAddresses = new HashSet<>();
                for (int i = 0; i < bindings.length(); i++) boundAddresses.add(binding(bindings.getJSONObject(i)).address);
                for (JSONArray retained : new JSONArray[]{preservedSuspensions, preservedVerifications})
                    for (int i = retained.length() - 1; i >= 0; i--) if (!boundAddresses.contains(retained.getString(i))) retained.remove(i);
                if (selection == null && !bindings.toString().equals(String.valueOf(target.optJSONArray("bindings")))) target.remove("gpuCursors");
            }
            target.put("fleetId", fleetId).put("profileId", profileId).put("address", request.candidate.address).put("port", request.candidate.port)
                    .put("keyBase64", request.identity.keyBase64).put("algorithm", request.identity.algorithm).put("pcId", verifiedPcId)
                    .put("credentialId", credentialId == null ? "" : credentialId).put("bindings", bindings)
                    .put("approved", approve).put("paused", false).put("rewardsAuthorized", pcOnly ? previousRewardsAuthorized : approve && preservedSuspensions.length() < bindings.length()).put("rewardsVerified", preservedVerifications)
                    .put("rewardsSuspended", preservedSuspensions).put("authorizationId", authorization)
                    .put("setupPending", !pcOnly && approve).put("lastSetupAttemptAtMs", expectedTarget == null ? 0 : expectedTarget.optLong("lastSetupAttemptAtMs"))
                    .put("status", pcOnly ? "binding-changed" : "authorization-required");
            if (pcOnly) target.put("paused", true);
            if (sudoPasswords.containsKey(request.candidate.endpointKey())) {
                target.put("sudoApproved", true).put("sudoPassword", new String(sudoPasswords.get(request.candidate.endpointKey()), StandardCharsets.UTF_8));
            } else if (!target.has("sudoApproved")) target.put("sudoApproved", false);
            if (selection != null) {
                if (!saveSelectedRegistration(expectedTarget, target, request, pcOnly, consentActive)) {
                    report.defer(request, "Saved approval or credentials changed; no additional access was granted"); continue;
                }
            } else if (pcOnly) store.savePcMonitoringTarget(target); else store.saveMonitoringTarget(target);
            if (approve) report.targets.add(new JSONObject(target.toString()));
        }
        return report;
    }
    /** A newly narrowed approval cannot overwrite a restriction received after inspection. */
    private boolean saveSelectedRegistration(JSONObject expected, JSONObject target,
            SshDiscovery.BatchRequest request, boolean pcOnly, java.util.function.BooleanSupplier consentActive) throws Exception {
        synchronized (store) {
            if (!consentActive.getAsBoolean() || storageBlocked || !automaticCredentialAllowed(target.optString("credentialId"))) return false;
            JSONObject current = store.findMonitoringPc(target.getString("fleetId"), target.getString("pcId"));
            if (expected == null ? current != null : current == null || !expected.toString().equals(current.toString())) return false;
            JSONObject remembered = store.credential(target.getString("credentialId"));
            if (remembered == null || !matchesRememberedCredential(request, remembered)) return false;
            if (!request.identity.keyBase64.equals(store.trustedKey(target.getString("profileId"), request.candidate.endpointKey()))) return false;
            if (!automaticPcStateAllowed(target.getString("fleetId"), target.getString("pcId"), expected, request.candidate)) return false;
            if (!consentActive.getAsBoolean()) return false;
            if (pcOnly) store.savePcMonitoringTarget(target); else store.saveMonitoringTarget(target);
            return true;
        }
    }
    /** A partial host choice cannot safely rewrite the older PC-wide setup/permission record. */
    private static String selectedRegistrationDeferral(JSONArray saved, JSONObject target, String fleetId,
            String pcId, SshDiscovery.BatchRequest request, SshDiscovery.Result selectedResult, InspectionSelection selection) {
        int matching = 0;
        for (int i = 0; i < saved.length(); i++) {
            JSONObject prior = saved.optJSONObject(i);
            if (prior != null && fleetId.equals(prior.optString("fleetId")) && pcId.equals(prior.optString("pcId"))) matching++;
        }
        if (matching > 1) return "Saved PC connection records need explicit repair";
        if (target == null) return null;
        try {
            validateTarget(target);
            List<NodeBinding> existingBindings = new ArrayList<>();
            JSONArray bindings = target.getJSONArray("bindings");
            for (int i = 0; i < bindings.length(); i++) existingBindings.add(binding(bindings.getJSONObject(i)));
            if (!selection.includesEveryBinding(pcId, existingBindings))
                return "This PC also has unselected hosts; existing monitoring was left unchanged";
            for (NodeBinding previous : existingBindings) {
                boolean exact = false;
                for (NodeBinding inspected : selectedResult.bindings)
                    if (previous.address.equals(inspected.address) && previous.key().equals(inspected.key())) { exact = true; break; }
                if (!exact) return "An existing node binding was not verified unchanged; existing monitoring was kept";
            }
            if (!request.candidate.address.equals(target.getString("address")) || request.candidate.port != target.getInt("port")
                    || !request.identity.keyBase64.equals(target.getString("keyBase64")))
                return "Changing a saved PC connection requires explicit repair";
            JSONArray suspended = target.optJSONArray("rewardsSuspended");
            if (target.optBoolean("aliasReplaced") || suspended == null || suspended.length() > 0
                    || !unusedInspection(target) && (!target.optBoolean("approved") || target.optBoolean("paused")
                    || !"active".equals(target.optString("status")) || !existingBindings.isEmpty() && !target.optBoolean("rewardsAuthorized")))
                return "Existing monitoring restrictions were kept; use explicit repair";
        } catch (Exception invalidTarget) { return "Saved monitoring records need explicit repair"; }
        return null;
    }
    void startSetup(List<JSONObject> targets) {
        if (!targets.isEmpty()) storageBlocked = false; // A new explicit approval was successfully persisted.
        for (JSONObject target : targets) blockedCredentialProfiles.remove(target.optString("credentialId"));
        handler.removeCallbacks(tick);
        handler.post(tick);
    }
    void pauseFleet(String fleetId) throws Exception {
        cancelActive();
        JSONArray targets;
        try { targets = store.pauseMonitoringFleet(fleetId); }
        catch (Exception unavailable) { storageBlocked = true; cancelActive(); throw unavailable; }
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            workers.execute(() -> { try {
                Set<String> roster = currentRoster(fleetId);
                JSONArray bindings = target.optJSONArray("bindings");
                if (bindings == null) return;
                for (int j = 0; j < bindings.length(); j++) {
                    try {
                        NodeBinding binding = binding(bindings.getJSONObject(j));
                        if (roster.contains(binding.address)) postObservation(fleetId, observation(target, binding, "paused"));
                    } catch (Exception invalidBinding) { /* Native revocation was already persisted atomically. */ }
                }
            } catch (Exception ignored) { /* Cached UI status cannot restore revoked native authority. */ } });
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
    void destroy() { destroyed = true; pause(); workers.shutdownNow(); ipWorker.shutdownNow(); }
    private synchronized void cancelActive() {
        generation++;
        synchronized (networks) { for (DiscoveryNetwork network : networks) network.cancel(); }
    }
    private void poll() {
        if (!foreground || destroyed || selectedFleet == null || storageBlocked) return;
        try {
            long now = System.currentTimeMillis();
            JSONArray targets = store.monitoringTargets();
            if (!reconcilingNetworkDelivery && ipInFlight.isEmpty()
                    && (reconciledNetworkGeneration != generation || now - lastNetworkReconciliationAt >= 60_000)) {
                reconcileNetworkDelivery(targets, now);
            }
            Map<String, JSONObject> eligible = new java.util.LinkedHashMap<>();
            Map<String, Long> oldest = new java.util.LinkedHashMap<>();
            Map<String, boolean[]> operations = new java.util.HashMap<>();
            Map<String, JSONObject> ipEligible = new java.util.LinkedHashMap<>();
            Map<String, Long> ipOldest = new java.util.LinkedHashMap<>();
            for (int i = 0; i < targets.length(); i++) {
                // A malformed record must not starve unrelated PCs. Node state is
                // deliberately checked only after admitting this PC's IP lane.
                try {
                    JSONObject target = targets.getJSONObject(i);
                    if (!selectedFleet.equals(target.optString("fleetId"))) continue;
                    validatePcTarget(target);
                    if (blockedCredentialProfiles.contains(target.getString("credentialId"))) continue;
                    String pc = target.getString("pcId"), fleet = target.getString("fleetId");
                    boolean pending = networkCache.pendingDelivery(fleet, pc);
                    long ipDeadline = pending ? networkCache.nextDeliveryAt(fleet, pc) : networkCache.nextReadAt(pc);
                    if (!ipInFlight.contains(pc) && permittedNetworkObservation(store, target)
                            && ipDeadline <= now && target.optLong("networkRetryAtMs") <= now) {
                        ipEligible.put(pc, target); ipOldest.put(pc, target.optLong("lastNetworkAttemptAtMs"));
                    }
                    if (inFlight.contains(target.getString("id"))) continue;
                    validateNodeTarget(target);
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
                } catch (Exception invalidTarget) { /* Preserve the record; use only independently validated PC authority. */ }
            }
            for (String pc : MonitoringPolicy.oldestFirst(ipOldest, MonitoringPolicy.IP_SLOTS - ipInFlight.size() - (reconcilingNetworkDelivery ? 1 : 0))) {
                try { dispatchNetwork(ipEligible.get(pc)); } catch (Exception unavailable) { /* The saved PC stays eligible under its own retry policy. */ }
            }
            for (String id : MonitoringPolicy.oldestFirst(oldest, MonitoringPolicy.NODE_SLOTS - inFlight.size())) {
                try {
                    boolean[] actions = operations.get(id); dispatch(eligible.get(id), actions[0], actions[1], actions[2]);
                } catch (Exception unavailable) { /* An independent node-state failure cannot cancel the PC IP lane. */ }
            }
        } catch (Exception ignored) { /* Vault unavailable: no credentials or endpoints are guessed. */ }
        handler.postDelayed(tick, inFlight.isEmpty() && ipInFlight.isEmpty() && !reconcilingNetworkDelivery ? 5000 : 1000);
    }
    private void dispatch(JSONObject target, boolean enable, boolean rewards, boolean gpu) throws Exception {
        String id = target.getString("id");
        if (!inFlight.add(id)) return;
        long now = System.currentTimeMillis();
        if (enable) target.put("lastSetupAttemptAtMs", now);
        if (rewards) target.put("lastRewardsAttemptAtMs", now);
        if (gpu) target.put("lastGpuAttemptAtMs", now);
        try { store.saveMonitoringProgress(target); }
        catch (Exception error) { inFlight.remove(id); throw error; }
        long run = generation;
        workers.execute(() -> {
            DiscoveryNetwork network = null;
            byte[] sudo = null;
            try {
                if (!activeTarget(run, target)) return;
                if (fixtureNodeOperation != null) { fixtureNodeOperation.run(target, enable, rewards, gpu); return; }
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
                networkTargets.put(network, target.getString("id"));
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
                    Set<String> deliveredRewards = new HashSet<>(), deliveredGpu = new HashSet<>();
                    List<SshDiscovery.MonitoringResult> results = SshDiscovery.monitor(network,
                            new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), ""),
                            new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm")), selected, sudo,
                            bindings, source(true), source(false), enable, rewards, gpu, cursors, rewardAddresses,
                            result -> {
                                try {
                                    boolean rateLimited = result.rewardsJson != null && "rate-limited".equals(new JSONObject(result.rewardsJson).optString("status"));
                                    String receipt = result.binding.address + "|" + (result.rewardsJson == null ? result.status : result.rewardsJson);
                                    if ((rateLimited || result.suspendRewards || result.retryAfterSeconds > 0 || MonitoringPolicy.pausesTarget(result.status))
                                            && receivedRestrictions.add(receipt)) {
                                        long seconds = MonitoringPolicy.providerDelaySeconds(rateLimited, result.retryAfterSeconds);
                                        long deadline = seconds > 0 ? MonitoringPolicy.retryAt(System.currentTimeMillis(), seconds) : 0;
                                        noteProviderRetry(deadline);
                                        boolean stop = MonitoringPolicy.pausesTarget(result.status) && !(result.suspendRewards && "authorization-required".equals(result.status));
                                        if (MonitoringPolicy.suspendsCredentialProfile(result.status)) blockCredentialProfile(target);
                                        store.restrictMonitoring(target, result.binding.address, result.suspendRewards, stop, result.status, deadline);
                                        if ("identity-changed".equals(result.status)) cancelTargetConnections(target.optString("id"));
                                        if (result.suspendRewards) { suspendedRewards.add(result.binding.address); verifiedRewards.remove(result.binding.address); }
                                        target.put("rewardsSuspended", new JSONArray(suspendedRewards)).put("rewardsVerified", new JSONArray(verifiedRewards));
                                        target.put("rewardsAuthorized", suspendedRewards.size() < bindings.size());
                                        if (stop) target.put("paused", true).put("status", result.status);
                                        if (deadline > 0) target.put("rewardsRetryAtMs", Math.max(target.optLong("rewardsRetryAtMs"), deadline));
                                    }
                                } catch (Exception failure) { storageBlocked = true; throw new IllegalStateException("Monitoring restrictions could not be saved"); }
                                // Restriction persistence precedes delivery. Recheck ownership for
                                // every source, including after a slow local HTTP response.
                                Set<String> accepted = MonitoringDelivery.deliver(target.optString("pcId"), System.currentTimeMillis(), result,
                                        () -> canDeliver(run, target, result),
                                        (kind, metadata, payload) -> {
                                            JSONObject update = sourceUpdate(metadata, kind, payload);
                                            if (!canDeliver(run, target, result)) throw new IllegalStateException("Monitoring delivery cancelled");
                                            postObservation(target.getString("fleetId"), update);
                                        });
                                if (accepted.contains("rewards")) deliveredRewards.add(result.binding.address);
                                if (accepted.contains("gpu")) deliveredGpu.add(result.binding.address);
                            }, () -> activeTarget(run, target) && !storageBlocked && Math.max(observedProviderRetryAt, store.rewardsRetryAtMs()) <= System.currentTimeMillis(),
                            null, () -> activeTarget(run, target) && !storageBlocked && permittedTarget(store, target, false));
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
                        JSONObject rewardReading = deliveredRewards.contains(result.binding.address) ? deliveredObject(result.rewardsJson) : null;
                        JSONObject gpuReading = deliveredGpu.contains(result.binding.address) ? deliveredObject(result.gpuJson) : null;
                        if (enable && (gpuReading == null || java.util.Arrays.asList("unavailable", "setup-failed", "not-enabled").contains(gpuReading.optString("status")))) setupComplete = false;
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
                if (network != null) { networkTargets.remove(network); networkCredentials.remove(network); networks.remove(network); network.close(); }
                inFlight.remove(id);
            }
        });
    }
    private void dispatchNetwork(JSONObject target) throws Exception {
        String pc = target.getString("pcId"), fleet = target.getString("fleetId");
        if (!ipInFlight.add(pc)) return;
        long run = generation;
        try { store.recordNetworkAttempt(target, System.currentTimeMillis()); }
        catch (Exception unavailable) { ipInFlight.remove(pc); throw unavailable; }
        ipWorker.execute(() -> {
            try {
                if (!canCollectNetwork(run, target)) return;
                if (!currentFleetHasPc(fleet, pc)) return;
                // Delivery retries consume no SSH connection or source timestamp, even
                // if the underlying source cache would otherwise be eligible again.
                PublicIpReader.Observation observation = networkCache.pendingDelivery(fleet, pc) ? networkCache.peek(pc) : null;
                if (observation == null) {
                    observation = networkCache.getOrRead(pc, () -> {
                        if (!canCollectNetwork(run, target)) throw new PublicNetworkCache.Aborted();
                        JSONObject saved = store.credential(target.getString("credentialId"));
                        if (saved == null) throw new PublicNetworkCache.Aborted();
                        try (SshDiscovery.Credential credential = DiscoveryController.decodeCredential(saved)) {
                            SshDiscovery.NetworkResult result = fixtureNetworkReader == null ? readPcNetwork(run, target, credential)
                                    : fixtureNetworkReader.read(target, credential, () -> canCollectNetwork(run, target));
                            try {
                                if (MonitoringPolicy.suspendsCredentialProfile(result.status)) blockCredentialProfile(target);
                                else if ("identity-changed".equals(result.status)) {
                                    store.restrictPcConnection(target, result.status);
                                    cancelTargetConnections(target.optString("id"));
                                }
                            } catch (Exception unavailable) {
                                storageBlocked = true;
                                cancelActive();
                                throw new IllegalStateException("PC connection restriction could not be saved");
                            }
                            if (result.observation != null && result.observation.retryAtMs > 0) networkCache.preserveRetry(pc, result.observation.retryAtMs);
                            if (!canCollectNetwork(run, target) || "cancelled".equals(result.status)) throw new PublicNetworkCache.Aborted();
                            if (result.observation == null) return new PublicIpReader.Observation(System.currentTimeMillis(), null, null, null, null, 0,
                                    PublicIpReader.SOURCE, "connection", "interface-read-failure", null);
                            return result.observation;
                        }
                    });
                }
                if (observation == null || observation.observedAtMs > System.currentTimeMillis() || !canCollectNetwork(run, target) || !currentFleetHasPc(fleet, pc)) return;
                networkCache.deliveryAttempt(fleet, pc);
                JSONObject update = sourceUpdate("{\"pcId\":\"" + pc + "\"}", "network", observation.json());
                if (!canCollectNetwork(run, target)) return;
                postObservation(fleet, update);
                if (canCollectNetwork(run, target)) networkCache.delivered(fleet, pc, observation);
            } catch (Exception unavailable) { /* Dated cached observations remain eligible for delivery retry. */ }
            finally { ipInFlight.remove(pc); }
        });
    }
    private SshDiscovery.NetworkResult readPcNetwork(long run, JSONObject target, SshDiscovery.Credential credential) throws Exception {
        DiscoveryNetwork network;
        try { network = DiscoveryNetwork.capture(activity); }
        catch (Exception unavailable) {
            if (!canCollectNetwork(run, target)) return new SshDiscovery.NetworkResult("cancelled", null);
            return new SshDiscovery.NetworkResult("network-unavailable", new PublicIpReader.Observation(System.currentTimeMillis(),
                    null, null, null, null, 0, PublicIpReader.SOURCE, "connection", "interface-read-failure", null));
        }
        networks.add(network); networkCredentials.put(network, target.getString("credentialId"));
        networkTargets.put(network, target.getString("id"));
        try {
            if (!canCollectNetwork(run, target)) { network.cancel(); return new SshDiscovery.NetworkResult("cancelled", null); }
            return SshDiscovery.readNetwork(network, new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), ""),
                    new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm")), credential,
                    () -> canCollectNetwork(run, target));
        } finally { networkTargets.remove(network); networkCredentials.remove(network); networks.remove(network); network.close(); }
    }
    private boolean currentFleetHasPc(String fleetId, String pcId) throws Exception {
        return currentFleetPcIds(fleetId).contains(pcId);
    }
    private Set<String> currentFleetPcIds(String fleetId) throws Exception {
        Set<String> result = new HashSet<>();
        JSONArray fleets = request("/mobile/fleets", null, 4 * 1024 * 1024).getJSONArray("fleets");
        for (int i = 0; i < fleets.length(); i++) {
            JSONObject fleet = fleets.getJSONObject(i);
            if (!fleetId.equals(fleet.getString("id"))) continue;
            JSONArray hosts = fleet.getJSONArray("hosts");
            for (int j = 0; j < hosts.length(); j++) {
                JSONObject pc = hosts.getJSONObject(j).optJSONObject("pcMetadata");
                if (pc != null && pc.optString("pcId").matches("[a-f0-9]{64}")) result.add(pc.getString("pcId"));
            }
        }
        return result;
    }
    /** Confirm native delivery receipts against the real receiver on resume, without source reads. */
    private void reconcileNetworkDelivery(JSONArray targets, long now) {
        long run = generation; String fleet = selectedFleet;
        reconcilingNetworkDelivery = true; reconciledNetworkGeneration = run; lastNetworkReconciliationAt = now;
        try {
            ipWorker.execute(() -> {
                try {
                    if (!active(run)) return;
                    JSONObject response = request("/mobile/observations?fleet=" + fleet, null, 4 * 1024 * 1024);
                    if (!(response.opt("schema") instanceof Number) || response.getDouble("schema") != 1
                            || !fleet.equals(response.optString("fleetId")) || !response.has("error") || !response.isNull("error")) return;
                    JSONArray records = response.getJSONArray("pcNetworks");
                    if (records.length() > 2048) return;
                    Map<String, PublicIpReader.Observation> received = new java.util.HashMap<>();
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject record = records.getJSONObject(i);
                        exactFields(record, new String[]{"pcId", "network", "receivedAtMs"}, new String[]{"lastGoodNetwork"});
                        String pcId = strictString(record, "pcId");
                        if (!pcId.matches("[a-f0-9]{64}") || received.containsKey(pcId)) throw new IllegalArgumentException("Invalid PC network receiver");
                        strictTime(record, "receivedAtMs", false);
                        PublicIpReader.Observation observation = receiverNetwork(record.getJSONObject("network"));
                        if (record.has("lastGoodNetwork")) receiverNetwork(record.getJSONObject("lastGoodNetwork"));
                        received.put(pcId, observation);
                    }
                    Set<String> associated = currentFleetPcIds(fleet);
                    if (!active(run)) return;
                    for (int i = 0; i < targets.length(); i++) {
                        JSONObject target = targets.optJSONObject(i);
                        if (target == null || !fleet.equals(target.optString("fleetId"))) continue;
                        String pcId = target.optString("pcId");
                        if (associated.contains(pcId) && canCollectNetwork(run, target))
                            networkCache.reconcileDelivery(fleet, pcId, received.get(pcId));
                    }
                } catch (Exception unavailable) { /* Only a complete validated receiver response can invalidate a receipt. */ }
                finally { reconcilingNetworkDelivery = false; }
            });
        } catch (java.util.concurrent.RejectedExecutionException stopped) { reconcilingNetworkDelivery = false; }
    }
    /** Parse fields explicitly; server JSON property order is not part of the wire contract. */
    static PublicIpReader.Observation receiverNetwork(JSONObject value) throws Exception {
        exactFields(value, new String[]{"schema", "observedAtMs", "status", "publicIp", "publicObservedAtMs", "privateIp", "privateObservedAtMs", "source"},
                new String[]{"publicFailure", "privateFailure", "httpStatus", "retryAtMs"});
        if (!(value.opt("schema") instanceof Number) || value.getDouble("schema") != 1) throw new IllegalArgumentException("Invalid network schema");
        long observed = strictTime(value, "observedAtMs", false);
        String publicIp = value.isNull("publicIp") ? null : strictString(value, "publicIp");
        String privateIp = value.isNull("privateIp") ? null : strictString(value, "privateIp");
        Long publicAt = value.isNull("publicObservedAtMs") ? null : strictTime(value, "publicObservedAtMs", false);
        Long privateAt = value.isNull("privateObservedAtMs") ? null : strictTime(value, "privateObservedAtMs", false);
        String status = strictString(value, "status"), source = strictString(value, "source");
        String publicFailure = value.has("publicFailure") ? strictString(value, "publicFailure") : null;
        String privateFailure = value.has("privateFailure") ? strictString(value, "privateFailure") : null;
        Integer httpStatus = value.has("httpStatus") ? Math.toIntExact(strictTime(value, "httpStatus", false)) : null;
        long retry = value.has("retryAtMs") ? strictTime(value, "retryAtMs", false) : 0;
        if (!status.equals(publicIp == null ? "unavailable" : "ok") || httpStatus != null && !"http-refusal".equals(publicFailure))
            throw new IllegalArgumentException("Invalid network status");
        return new PublicIpReader.Observation(observed, publicIp, publicAt, privateIp, privateAt, retry, source, publicFailure, privateFailure, httpStatus);
    }
    private static void exactFields(JSONObject value, String[] required, String[] optional) throws Exception {
        Set<String> allowed = new HashSet<>(java.util.Arrays.asList(required)); allowed.addAll(java.util.Arrays.asList(optional));
        for (String key : required) if (!value.has(key)) throw new IllegalArgumentException("Missing network field");
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("Unknown network field");
    }
    private static String strictString(JSONObject value, String key) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof String)) throw new IllegalArgumentException("Invalid network string");
        return (String) field;
    }
    private static long strictTime(JSONObject value, String key, boolean zeroAllowed) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof Number)) throw new IllegalArgumentException("Invalid network number");
        double number = ((Number) field).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number) || number < (zeroAllowed ? 0 : 1) || number > PublicIpReader.MAX_SAFE_INTEGER)
            throw new IllegalArgumentException("Invalid network time");
        return ((Number) field).longValue();
    }
    private boolean canCollectNetwork(long run, JSONObject target) {
        return activeTarget(run, target) && !storageBlocked && permittedNetworkObservation(store, target) && activeTarget(run, target);
    }
    static boolean permittedNetworkObservation(DiscoveryStore store, JSONObject expected) { return permittedTarget(store, expected, true); }
    private static boolean permittedTarget(DiscoveryStore store, JSONObject expected, boolean pcNetwork) {
        try {
            validatePcTarget(expected);
            JSONArray targets = store.monitoringTargets();
            for (int i = 0; i < targets.length(); i++) {
                JSONObject current = targets.optJSONObject(i);
                if (current == null || !current.optString("id").equals(expected.getString("id"))) continue;
                validatePcTarget(current);
                if (!(pcNetwork ? MonitoringPolicy.pcNetworkPermitted(current.optBoolean("approved"), current.optBoolean("paused"), current.optString("status"))
                        : current.optBoolean("approved") && !current.optBoolean("paused"))
                        || current.optBoolean("aliasReplaced") || !current.getString("pcId").equals(expected.getString("pcId"))
                        || !current.optString("authorizationId").equals(expected.optString("authorizationId"))
                        || !current.getString("credentialId").equals(expected.getString("credentialId"))
                        || !current.getString("keyBase64").equals(expected.getString("keyBase64"))
                        || !current.getString("address").equals(expected.getString("address")) || current.getInt("port") != expected.getInt("port")
                        || store.credential(current.getString("credentialId")) == null) return false;
                String endpoint = new DiscoveryPolicy.Candidate(current.getString("address"), current.getInt("port"), "").endpointKey();
                return current.getString("keyBase64").equals(store.trustedKey(current.getString("profileId"), endpoint));
            }
        } catch (Exception unavailable) { return false; }
        return false;
    }
    private void cancelTargetConnections(String targetId) {
        for (Map.Entry<DiscoveryNetwork, String> connection : networkTargets.entrySet())
            if (targetId.equals(connection.getValue())) connection.getKey().cancel();
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
        if (activeTarget(run, target)) store.saveMonitoringProgress(target);
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
        if ("identity-changed".equals(status)) {
            store.restrictPcConnection(target, status); cancelTargetConnections(target.optString("id"));
        }
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
    static JSONObject sourceUpdate(String metadata, String kind, String payload) throws Exception {
        JSONObject update = new JSONObject(metadata);
        if (payload != null) {
            if (!java.util.Arrays.asList("network", "rewards", "gpu").contains(kind)) throw new IllegalArgumentException("Invalid source");
            org.json.JSONTokener source = new org.json.JSONTokener(payload);
            Object value = source.nextValue();
            if (!(value instanceof JSONObject) || source.nextClean() != 0) throw new IllegalArgumentException("Invalid source object");
            update.put(kind, value);
        }
        return update;
    }
    private boolean canDeliver(long run, JSONObject expected, SshDiscovery.MonitoringResult result) {
        return activeTarget(run, expected) && !storageBlocked && permittedObservation(store, expected, result) && activeTarget(run, expected);
    }
    static boolean permittedObservation(DiscoveryStore store, JSONObject expected, SshDiscovery.MonitoringResult result) {
        try {
            JSONArray targets = store.monitoringTargets();
            for (int i = 0; i < targets.length(); i++) {
                JSONObject current = targets.getJSONObject(i);
                if (!current.getString("id").equals(expected.getString("id"))) continue;
                if (!current.optBoolean("approved") || current.optBoolean("aliasReplaced")
                        || !current.optString("authorizationId").equals(expected.optString("authorizationId"))
                        || !current.getString("credentialId").equals(expected.getString("credentialId"))
                        || !current.getString("keyBase64").equals(expected.getString("keyBase64"))
                        || store.credential(current.getString("credentialId")) == null) return false;
                boolean restriction = MonitoringPolicy.pausesTarget(result.status);
                if (current.optBoolean("paused") && (!restriction || !current.optString("status").equals(result.status))) return false;
                String endpoint = new DiscoveryPolicy.Candidate(current.getString("address"), current.getInt("port"), "").endpointKey();
                if (!current.getString("keyBase64").equals(store.trustedKey(current.getString("profileId"), endpoint))) return false;
                for (NodeBinding saved : recoveryBindings(current, Collections.singleton(result.binding.address)))
                    if (saved.key().equals(result.binding.key())) return true;
                return false;
            }
        } catch (Exception unavailable) { return false; }
        return false;
    }
    private static JSONObject deliveredObject(String value) {
        try { return value == null ? null : new JSONObject(value); } catch (Exception invalid) { return null; }
    }
    private JSONObject observation(JSONObject target, NodeBinding binding, String status) throws Exception {
        return new JSONObject(MonitoringDelivery.update(target.getString("pcId"), System.currentTimeMillis(), binding, status));
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
