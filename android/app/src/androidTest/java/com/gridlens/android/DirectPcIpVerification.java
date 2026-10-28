package com.gridlens.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Real Android SSH/service/API tests with no prior native monitoring target. */
final class DirectPcIpVerification {
    static void verify(Instrumentation driver, Bundle arguments) {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        final String namespace = "direct-pc-ip-verification";
        DiscoveryStore store = null; MonitoringController monitoring = null;
        String origin = null, token = null, fleet = null, wrongFleet = null;
        List<String> pcIds = new ArrayList<>(); com.jcraft.jsch.KeyPair wrongKey = null;
        try {
            require(BuildConfig.DEBUG, "Debug fixture required");
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"), "Disposable emulator required");
            int port = Integer.parseInt(arguments.getString("fixturePort", "0"));
            require(port > 1024 && port <= 65535, "Bounded generated SSH fixture port required");
            SshDiscovery.Identity identity = new SshDiscovery.Identity(arguments.getString("fixtureKey"), arguments.getString("fixtureAlgorithm", "ssh-rsa"));
            String pcId = MonitoringController.pcId(identity); pcIds.add(pcId);
            require(!new File(driver.getTargetContext().getNoBackupFilesDir(), namespace).exists(), "Unused direct IP fixture namespace required");
            MainActivity activity = (MainActivity) driver.startActivitySync(new Intent(driver.getTargetContext(), MainActivity.class)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh"));
            JSONObject ready = awaitRuntime(driver);
            origin = "http://127.0.0.1:" + ready.getInt("port"); token = ready.getString("token");
            require(request(origin, token, "/mobile/fleets", null).getJSONArray("fleets").length() == 0, "Direct IP verification requires no existing fleet data");
            store = new DiscoveryStore(activity, namespace);
            String profile = store.saveProfile(null, "Direct PC IP fixture", new JSONArray().put("10.0.2.0/24"), new JSONArray().put(port));
            store.trust(profile, "[10.0.2.2]:" + port, identity.keyBase64);
            String credential = store.saveCredential(new JSONObject().put("name", "Generated direct fixture").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), identity.keyBase64);
            long at = System.currentTimeMillis() - 1000;
            fleet = createFleet(origin, token, pcId, at, "Direct IP fixture");
            seedHistoricalNodes(origin, token, fleet, pcId, at);
            String originalNodes = request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("hosts").toString();
            require(store.monitoringTargets().length() == 0, "Fixture unexpectedly has native monitoring authority");
            monitoring = new MonitoringController(activity, store, origin, token);
            PcIpRepair repair = new PcIpRepair(activity, store, monitoring, origin, token);
            PublicNetworkCache cache = monitoring.pcIpCache();
            clearPcCache(activity, pcId);
            PcIpRepair.Plan plan = repair.inspect(fleet, pcId);
            require(plan.endpoints.size() == 1 && plan.endpoints.get(0).needsApproval
                    && credential.equals(plan.endpoints.get(0).credentialId), "Saved pin/credential without a native target was not identified for explicit approval");
            long connections = DiscoveryNetwork.startedConnectionsForTesting();
            PcIpRepair.Result declined = repair.execute(plan, plan.endpoints.get(0), credential, false, null, () -> true);
            require("confirmation-required".equals(declined.status) && !declined.delivered
                    && DiscoveryNetwork.startedConnectionsForTesting() == connections && store.monitoringTargets().length() == 0,
                    "Missing approval started a connection or created authority");
            requireScopeFailure(repair, "invalid", pcId, "invalid-scope");
            requireScopeFailure(repair, fleet, "f".repeat(64), "unknown-pc");
            require(DiscoveryNetwork.startedConnectionsForTesting() == connections, "Invalid PC scope started SSH");

            PcIpRepair.Result measured = execute(activity, repair, plan, credential, true, () -> true);
            require("measured".equals(measured.status) && measured.delivered && measured.measured && measured.connectionVerified,
                    "Direct approved PC-only read failed: " + measured.status);
            require("8.8.8.8".equals(measured.observation.publicIp) && "192.168.10.101".equals(measured.observation.privateIp),
                    "Direct host reading returned wrong fixture addresses");
            require(DiscoveryNetwork.startedConnectionsForTesting() == connections + 1, "Direct check did not use exactly one real SSH connection");
            JSONObject approved = store.monitoringTargets().getJSONObject(0);
            require(approved.getBoolean("approved") && approved.getBoolean("paused") && "binding-changed".equals(approved.getString("status"))
                    && approved.getJSONArray("bindings").length() == 0 && !approved.getBoolean("rewardsAuthorized"),
                    "PC-only approval granted node/rewards authority");
            for (int index = 1; index <= 3; index++) {
                JSONObject reading = request(origin, token, "/mobile/observations?fleet=" + fleet + "&address=" + "1".repeat(31) + index, null);
                require(reading.getJSONArray("pcNetworks").length() == 1 && pcId.equals(reading.getJSONArray("pcNetworks").getJSONObject(0).getString("pcId")),
                        "Direct PC reading did not cover all three associated hosts");
            }
            require(originalNodes.equals(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("hosts").toString()),
                    "Direct IP delivery rewrote historical node/reward/GPU records");

            connections = DiscoveryNetwork.startedConnectionsForTesting();
            plan = repair.inspect(fleet, pcId);
            require(!plan.endpoints.get(0).needsApproval, "Approved PC still requires another grant");
            PcIpRepair.Result cached = execute(activity, repair, plan, credential, false, () -> true);
            require("cached".equals(cached.status) && cached.delivered && !cached.measured && !cached.connectionVerified
                    && cached.observation.json().equals(measured.observation.json()) && DiscoveryNetwork.startedConnectionsForTesting() == connections,
                    "Repeated check reran curl or advanced its cached source timestamp");
            // A one-time alternate credential for an otherwise healthy approved
            // connection must not replace its node scope or saved connection.
            JSONObject healthy = store.findMonitoringPc(fleet, pcId);
            JSONArray healthyBindings = new JSONArray();
            for (int i = 1; i <= 3; i++) healthyBindings.put(new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                    .put("nodeContainerId", "a".repeat(63) + i).put("address", "1".repeat(31) + i));
            healthy.put("paused", false).put("status", "active").put("bindings", healthyBindings);
            store.saveMonitoringTarget(healthy);
            String healthySnapshot = store.findMonitoringPc(fleet, pcId).toString();
            String alternate = store.saveCredential(new JSONObject().put("name", "Alternate correct fixture").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), null);
            plan = repair.inspect(fleet, pcId);
            require(PcIpRepair.oneTimeConnection(plan.endpoints.get(0), alternate), "Healthy alternative was not scoped to one check");
            require("confirmation-required".equals(repair.execute(plan, plan.endpoints.get(0), alternate, false, null, () -> true).status),
                    "An alternate credential was used without native confirmation");
            PcIpRepair.Result alternateResult = execute(activity, repair, plan, alternate, true, () -> true);
            require(alternateResult.delivered && alternateResult.connectionVerified && !alternateResult.measured
                    && healthySnapshot.equals(store.findMonitoringPc(fleet, pcId).toString()),
                    "Alternate credential check replaced healthy node/connection authority");
            connections = DiscoveryNetwork.startedConnectionsForTesting();
            long wait = System.currentTimeMillis() + 3_600_000;
            cache.preserveRetry(pcId, wait);
            store.pauseMonitoringFleet(fleet);
            plan = repair.inspect(fleet, pcId);
            require(plan.endpoints.get(0).needsApproval, "Manual pause was ignored");
            require("confirmation-required".equals(repair.execute(plan, plan.endpoints.get(0), credential, false, null, () -> true).status),
                    "Manual pause was bypassed without explicit native confirmation");
            PcIpRepair.Result restored = execute(activity, repair, plan, credential, true, () -> true);
            require("cached".equals(restored.status) && restored.connectionVerified && !restored.measured && restored.delivered
                    && restored.retryAtMs >= wait && restored.observation.json().equals(measured.observation.json())
                    && DiscoveryNetwork.startedConnectionsForTesting() == connections + 1,
                    "Restoring paused PC authority did not verify SSH while honoring the provider wait");
            require(cache.providerRetryAt(pcId) >= wait, "Direct approval shortened a provider wait");

            // A permission change while the native confirmation was open invalidates its plan.
            plan = repair.inspect(fleet, pcId);
            store.pauseMonitoringFleet(fleet);
            connections = DiscoveryNetwork.startedConnectionsForTesting();
            PcIpRepair.Result stale = execute(activity, repair, plan, credential, false, () -> true);
            require(!stale.delivered && !stale.measured && "connection-changed".equals(stale.status)
                    && !store.monitoringTargets().getJSONObject(0).getBoolean("approved")
                    && DiscoveryNetwork.startedConnectionsForTesting() == connections, "A stale plan restored revoked authority");
            plan = repair.inspect(fleet, pcId);
            PcIpRepair.Result cancelled = execute(activity, repair, plan, credential, true, () -> false);
            require("cancelled".equals(cancelled.status) && DiscoveryNetwork.startedConnectionsForTesting() == connections,
                    "Background cancellation started a direct SSH connection");

            // Missing/invalid node state is preserved by explicit PC-only restoration.
            JSONObject malformed = store.monitoringTargets().getJSONObject(0);
            malformed.remove("bindings");
            seedTargets(store, new JSONArray().put("unrelated-malformed-record").put(malformed));
            plan = repair.inspect(fleet, pcId);
            PcIpRepair.Result repaired = execute(activity, repair, plan, credential, true, () -> true);
            require(repaired.delivered && repaired.connectionVerified && !repaired.measured
                    && !store.findMonitoringPc(fleet, pcId).has("bindings"), "Direct IP repair required or replaced node-binding data");

            // An explicitly selected rejected credential still pauses that profile durably.
            String badCredential = store.saveCredential(new JSONObject().put("name", "Rejected fixture").put("username", "fixture")
                    .put("password", "wrong-fixture-password").put("key", "").put("passphrase", ""), null);
            JSONObject badTarget = new JSONObject(store.findMonitoringPc(fleet, pcId).toString()).put("credentialId", badCredential)
                    .put("approved", true).put("paused", true).put("status", "credentials-required");
            JSONObject badSibling = new JSONObject(badTarget.toString()).put("id", UUID.randomUUID().toString())
                    .put("fleetId", UUID.randomUUID().toString()).put("paused", false).put("status", "active");
            seedTargets(store, new JSONArray().put("unrelated-malformed-record").put(badTarget).put(badSibling));
            plan = repair.inspect(fleet, pcId);
            // A rejected saved credential requires a real new authentication after explicit confirmation, even with cache.
            PcIpRepair.Result rejected = execute(activity, repair, plan, badCredential, true, () -> true);
            require("credentials-required".equals(rejected.status) && !rejected.delivered && !rejected.measured,
                    "Real SSH credential rejection was hidden by a cache hit");
            JSONObject durableDenied = new DiscoveryStore(activity, namespace).findMonitoringPc(fleet, pcId);
            require(durableDenied.getBoolean("paused") && "credentials-required".equals(durableDenied.getString("status")),
                    "Shared credential rejection was not persisted");
            JSONObject siblingDenied = new DiscoveryStore(activity, namespace).findMonitoringTarget(badSibling.getString("id"));
            require(siblingDenied.getBoolean("paused") && "credentials-required".equals(siblingDenied.getString("status")),
                    "Direct credential rejection did not pause its shared profile across fleets");

            wrongKey = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
            SshDiscovery.Identity wrongIdentity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(wrongKey.getPublicKeyBlob()), "ssh-rsa");
            String wrongPc = MonitoringController.pcId(wrongIdentity); pcIds.add(wrongPc);
            String wrongProfile = store.saveProfile(null, "Changed pin fixture", new JSONArray().put("10.0.2.0/24"), new JSONArray().put(port));
            store.trust(wrongProfile, "[10.0.2.2]:" + port, wrongIdentity.keyBase64);
            store.setOverride(wrongIdentity.keyBase64, credential);
            wrongFleet = createFleet(origin, token, wrongPc, System.currentTimeMillis(), "Wrong pin fixture");
            PcIpRepair.Plan wrongPlan = repair.inspect(wrongFleet, wrongPc);
            PcIpRepair.Result wrong = execute(activity, repair, wrongPlan, credential, true, () -> true);
            require("identity-changed".equals(wrong.status) && !wrong.delivered && !wrong.measured
                    && store.findMonitoringPc(wrongFleet, wrongPc) == null, "Changed SSH pin was accepted by direct repair");

            // No cache + explicit provider wait can verify authority, but cannot run curl.
            seedTargets(store, new JSONArray()); clearPcCache(activity, pcId); cache.preserveRetry(pcId, wait);
            plan = repair.inspect(fleet, pcId);
            connections = DiscoveryNetwork.startedConnectionsForTesting();
            PcIpRepair.Result deferred = execute(activity, repair, plan, credential, true, () -> true);
            require("provider-wait".equals(deferred.status) && deferred.connectionVerified && !deferred.measured && !deferred.delivered
                    && deferred.observation == null && deferred.retryAtMs >= wait && DiscoveryNetwork.startedConnectionsForTesting() == connections + 1,
                    "A provider wait without cache was bypassed or reported as a measurement");
            PcIpRepair.Plan beforeForget = repair.inspect(fleet, pcId);
            store.forgetCredentials(); connections = DiscoveryNetwork.startedConnectionsForTesting();
            PcIpRepair.Result forgotten = execute(activity, repair, beforeForget, credential, false, () -> true);
            require("credentials-required".equals(forgotten.status) && !forgotten.delivered
                    && DiscoveryNetwork.startedConnectionsForTesting() == connections && store.monitoringTargets().length() == 0,
                    "Forgotten credentials were reused or recreated");
            require(originalNodes.equals(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("hosts").toString()),
                    "Direct repair scenarios changed historical node data");
            verifyRouteGuards(driver, activity, origin, fleet, pcId);
            verifyNativeUiJourney(driver, activity, origin, token, fleet, pcId, identity, port, originalNodes);
            require(DiscoveryNetwork.startedSearchesForTesting() == 0, "Direct PC checks started a network scan");
            result.putString("stream", "PASS: direct native PC-only repair from saved pin/credential with no target, three-host delivery, unchanged node history, cache reuse, explicit pause restoration, stale-plan/revocation/cancellation, malformed bindings, credential rejection, changed SSH pin, missing credentials, provider wait, gesture/scope route guards, and actual WebView action→native confirmation→SSH→three rendered IP cells. Actual Android SSH; no operator PC or public provider calls.\n");
            outcome = Activity.RESULT_OK;
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL (direct PC IP): " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally {
            if (monitoring != null) { MonitoringController ending = monitoring; driver.runOnMainSync(ending::destroy); }
            if (origin != null && token != null) for (String remove : new String[]{fleet, wrongFleet}) if (remove != null)
                try { request(origin, token, "/mobile/fleets", new JSONObject().put("op", "delete").put("id", remove)); } catch (Exception ignored) { }
            if (store != null) try { store.forgetCredentials(); } catch (Exception ignored) { }
            if (wrongKey != null) wrongKey.dispose();
            for (String pc : pcIds) clearPcCache(driver.getTargetContext(), pc);
            File directory = new File(driver.getTargetContext().getNoBackupFilesDir(), namespace);
            File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
        }
        driver.finish(outcome, result);
    }
    private static PcIpRepair.Result execute(MainActivity activity, PcIpRepair repair, PcIpRepair.Plan plan, String credential,
            boolean approve, java.util.function.BooleanSupplier permitted) throws Exception {
        try (DiscoveryNetwork network = DiscoveryNetwork.capture(activity)) {
            return repair.execute(plan, plan.endpoints.get(0), credential, approve, network, permitted);
        }
    }
    private static void requireScopeFailure(PcIpRepair repair, String fleet, String pc, String expected) throws Exception {
        try { repair.inspect(fleet, pc); throw new AssertionError("Invalid scope was accepted"); }
        catch (PcIpRepair.Failure failure) { require(expected.equals(failure.status), "Wrong invalid-scope result"); }
    }
    private static JSONObject awaitRuntime(Instrumentation driver) throws Exception {
        long until = android.os.SystemClock.elapsedRealtime() + 60000;
        while (android.os.SystemClock.elapsedRealtime() < until) {
            JSONObject ready = NodeRuntime.ready(driver.getTargetContext()); if (ready != null) return ready; Thread.sleep(100);
        }
        throw new AssertionError("Embedded runtime did not start");
    }
    private static String createFleet(String origin, String token, String pc, long at, String name) throws Exception {
        JSONArray hosts = new JSONArray(), metadata = new JSONArray();
        for (int i = 1; i <= 3; i++) {
            String address = "1".repeat(31) + i; hosts.put(address);
            metadata.put(new JSONObject().put("address", address).put("pcId", pc).put("pcName", "direct-ip-fixture")
                    .put("ipAddresses", new JSONArray().put("10.0.2.2")).put("observedAtMs", at));
        }
        return request(origin, token, "/mobile/fleets", new JSONObject().put("op", "create").put("name", name)
                .put("addresses", hosts).put("pcMetadata", metadata)).getString("fleetId");
    }
    private static void seedHistoricalNodes(String origin, String token, String fleet, String pc, long at) throws Exception {
        JSONArray updates = new JSONArray();
        for (int i = 1; i <= 3; i++) {
            String address = "1".repeat(31) + i;
            NodeBinding binding = new NodeBinding("docker", null, "a".repeat(63) + i, address);
            JSONObject update = new JSONObject(MonitoringDelivery.update(pc, at, binding, "unsupported"));
            update.put("rewards", new JSONObject().put("schema", 1).put("address", address).put("observedAtMs", at).put("status", "ok").put("amount", "1.234567"));
            update.put("gpu", new JSONObject(NodeGpuCollector.unavailable(binding, "unsupported"))); updates.put(update);
        }
        request(origin, token, "/mobile/observations", new JSONObject().put("schema", 1).put("fleetId", fleet).put("updates", updates));
    }
    private static void seedTargets(DiscoveryStore store, JSONArray targets) throws Exception {
        java.lang.reflect.Method read = DiscoveryStore.class.getDeclaredMethod("credentials"); read.setAccessible(true);
        JSONObject value = new JSONObject(((JSONObject) read.invoke(store)).toString()).put("monitoringTargets", targets);
        java.lang.reflect.Method write = DiscoveryStore.class.getDeclaredMethod("saveCredentials", JSONObject.class); write.setAccessible(true); write.invoke(store, value);
    }
    private static void clearPcCache(android.content.Context context, String pc) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith(pc)) editor.remove(key);
        require(editor.commit(), "Fixture network cache cleanup failed");
    }
    private static void verifyRouteGuards(Instrumentation driver, MainActivity activity, String origin, String fleet, String pc) throws Exception {
        String base = origin + "/mobile/ip-check?fleet=" + fleet + "&pc=" + pc;
        long connections = DiscoveryNetwork.startedConnectionsForTesting();
        String[] invalid = {base + "&fleet=" + fleet, base + "&pc=" + pc, base + "&unexpected=yes", base + "#fragment",
                origin + "/mobile/ip-check?fleet=invalid&pc=" + pc, origin + "/mobile/ip-check?fleet=" + fleet + "&pc=invalid"};
        driver.runOnMainSync(() -> {
            WebViewClient client = activity.webViewForTesting().getWebViewClient();
            for (String url : invalid) require(client.shouldOverrideUrlLoading(activity.webViewForTesting(), resource(url, true, true)), "Invalid native action was allowed to navigate");
            require(client.shouldOverrideUrlLoading(activity.webViewForTesting(), resource(base, true, false)), "Gestureless native action was accepted");
            require(client.shouldOverrideUrlLoading(activity.webViewForTesting(), resource(base, false, true)), "Subframe native action was accepted");
        });
        driver.waitForIdleSync();
        require(!hasText(driver, "PC IP check") && !hasText(driver, "Checking saved PC")
                && !hasText(driver, "Saved data unavailable") && !hasText(driver, "Saved credentials unavailable")
                && DiscoveryNetwork.startedConnectionsForTesting() == connections,
                "Invalid route started native IP work");
        driver.runOnMainSync(() -> require(activity.webViewForTesting().getWebViewClient().shouldOverrideUrlLoading(activity.webViewForTesting(), resource(base, true, true)),
                "Valid native route did not intercept navigation"));
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (!hasText(driver, "No saved trusted SSH endpoint matches this PC.") && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        require(hasText(driver, "No saved trusted SSH endpoint matches this PC.") && DiscoveryNetwork.startedConnectionsForTesting() == connections,
                "Valid route did not surface the real missing native connection without SSH");
        driver.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
    }
    /** Seed only the proven-empty default vault of this disposable emulator, then tap the actual WebView action. */
    private static void verifyNativeUiJourney(Instrumentation driver, MainActivity activity, String origin, String token,
            String fleet, String pc, SshDiscovery.Identity identity, int port, String originalNodes) throws Exception {
        java.lang.reflect.Field discoveryField = MainActivity.class.getDeclaredField("discovery"); discoveryField.setAccessible(true);
        DiscoveryController nativeDiscovery = (DiscoveryController) discoveryField.get(activity);
        java.lang.reflect.Field storeField = DiscoveryController.class.getDeclaredField("store"); storeField.setAccessible(true);
        DiscoveryStore nativeStore = (DiscoveryStore) storeField.get(nativeDiscovery);
        require(nativeStore != null && nativeStore.knownEndpoints().length() == 0 && nativeStore.credentialProfiles().length() == 0
                && nativeStore.monitoringTargets().length() == 0, "Native UI fixture requires an empty default vault");
        String profile = nativeStore.saveProfile(null, "Direct IP UI fixture", new JSONArray().put("10.0.2.0/24"), new JSONArray().put(port));
        java.util.concurrent.atomic.AtomicReference<android.webkit.WebViewClient> capturedClient = new java.util.concurrent.atomic.AtomicReference<>();
        driver.runOnMainSync(() -> capturedClient.set(activity.webViewForTesting().getWebViewClient()));
        android.webkit.WebViewClient originalClient = capturedClient.get();
        try {
            nativeStore.trust(profile, "[10.0.2.2]:" + port, identity.keyBase64);
            nativeStore.saveCredential(new JSONObject().put("name", "Native UI fixture").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), identity.keyBase64);
            // This is a new isolated test phase. Remove only the generated PC's prior
            // fixture caches, so the UI must perform and display a new measurement.
            clearPcCache(activity, pc);
            JSONArray addresses = new JSONArray(), removed = new JSONArray(), restored = new JSONArray(); long at = System.currentTimeMillis();
            for (int i = 1; i <= 3; i++) {
                String address = "1".repeat(31) + i; addresses.put(address);
                removed.put(new JSONObject().put("address", address).put("pcId", JSONObject.NULL).put("pcName", JSONObject.NULL)
                        .put("ipAddresses", new JSONArray()).put("observedAtMs", at));
                restored.put(new JSONObject().put("address", address).put("pcId", pc).put("pcName", "direct-ip-fixture")
                        .put("ipAddresses", new JSONArray().put("10.0.2.2")).put("observedAtMs", at));
            }
            request(origin, token, "/mobile/fleets", new JSONObject().put("op", "merge").put("id", fleet).put("addresses", addresses).put("pcMetadata", removed));
            request(origin, token, "/mobile/fleets", new JSONObject().put("op", "merge").put("id", fleet).put("addresses", addresses).put("pcMetadata", restored));
            long absentDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks").length() != 0
                    && android.os.SystemClock.elapsedRealtime() < absentDeadline) Thread.sleep(100);
            require(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks").length() == 0, "UI fixture did not start with blank PC IP");
            driver.runOnMainSync(() -> {
                activity.webViewForTesting().setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, WebResourceRequest request) {
                        return originalClient.shouldOverrideUrlLoading(view, request);
                    }
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, WebResourceRequest request) {
                        String path = request.getUrl().getPath();
                        if (path != null && path.startsWith("/preview/") && path.endsWith(".json")
                                && !path.equals("/preview/hosts.json") && !path.equals("/preview/connection.json"))
                            return new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                    new java.io.ByteArrayInputStream("{\"hosts\":[]}".getBytes(StandardCharsets.UTF_8)));
                        return originalClient.shouldInterceptRequest(view, request);
                    }
                    @Override public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap icon) { originalClient.onPageStarted(view, url, icon); }
                    @Override public void onPageFinished(android.webkit.WebView view, String url) { originalClient.onPageFinished(view, url); }
                    @Override public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean reload) { originalClient.doUpdateVisitedHistory(view, url, reload); }
                });
                activity.webViewForTesting().loadUrl(origin + "/?scenario=fleet:" + fleet);
            });
            awaitJavascript(driver, activity, "document.querySelectorAll('#fleet-rows > tr[data-host]').length===3");
            javascript(driver, activity, "document.querySelector('#fleet-rows > tr[data-host] td[data-col=ip] .host-details').click()");
            awaitJavascript(driver, activity, "!!document.querySelector('.mobile-ip-check-action')");
            String label = (String) new org.json.JSONTokener(javascript(driver, activity,
                    "document.querySelector('.mobile-ip-check-action').textContent")).nextValue();
            long connections = DiscoveryNetwork.startedConnectionsForTesting();
            require(tapText(driver, label), "Could not tap actual WebView PC IP action; visible=" + visibleText(driver));
            awaitText(driver, "Allow IP checks for direct-ip-fixture?");
            require(DiscoveryNetwork.startedConnectionsForTesting() == connections, "Actual popup action connected before native approval");
            require(clickText(driver, "Verify and allow IP"), "Could not confirm native PC-only approval; visible=" + visibleText(driver));
            awaitText(driver, "IP check: direct-ip-fixture");
            awaitText(driver, "Public IP: 8.8.8.8");
            require(clickText(driver, "OK"), "Could not close direct IP result");
            awaitJavascript(driver, activity, "document.querySelectorAll('#fleet-rows > tr[data-host]').length===3");
            for (int i = 0; i < 6 && !"\"full\"".equals(javascript(driver, activity, "document.getElementById('ip-display-toggle').dataset.mode")); i++)
                javascript(driver, activity, "document.getElementById('ip-display-toggle').click()");
            awaitJavascript(driver, activity, "Array.from(document.querySelectorAll('#fleet-rows > tr[data-host] td[data-col=ip]')).filter(e=>e.textContent==='8.8.8.8 / 192.168.10.101').length===3");
            require(DiscoveryNetwork.startedConnectionsForTesting() == connections + 1, "Native UI IP repair started duplicate or node connections");
            require(originalNodes.equals(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("hosts").toString()),
                    "Native UI IP repair changed node/reward/GPU history");
            JSONObject target = nativeStore.findMonitoringPc(fleet, pc);
            require(target != null && target.getBoolean("paused") && !target.getBoolean("rewardsAuthorized") && target.getJSONArray("bindings").length() == 0,
                    "Actual UI action granted node authority");
        } finally {
            driver.runOnMainSync(() -> {
                nativeDiscovery.paused();
                activity.webViewForTesting().loadUrl(origin + "/?scenario=fresh");
                activity.webViewForTesting().setWebViewClient(originalClient);
            });
            nativeStore.forgetCredentials(); nativeStore.deleteProfile(profile); clearPcCache(activity, pc);
        }
    }
    private static void awaitJavascript(Instrumentation driver, MainActivity activity, String expression) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(javascript(driver, activity, expression))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Direct IP WebView condition failed: " + expression);
    }
    private static String javascript(Instrumentation driver, MainActivity activity, String expression) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>();
        driver.runOnMainSync(() -> activity.webViewForTesting().evaluateJavascript(expression, result -> { value.set(result); done.countDown(); }));
        require(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "Direct IP WebView JavaScript timed out"); return value.get();
    }
    private static void awaitText(Instrumentation driver, String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (!hasText(driver, text) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        require(hasText(driver, text), "Direct IP native text missing: " + text + "; visible=" + visibleText(driver));
    }
    private static String visibleText(Instrumentation driver) {
        StringBuilder text = new StringBuilder();
        collectVisibleText(driver.getUiAutomation().getRootInActiveWindow(), text);
        return text.toString();
    }
    private static void collectVisibleText(AccessibilityNodeInfo node, StringBuilder text) {
        if (node == null) return;
        try {
            if (!node.isPassword() && !"android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                    && node.getText() != null && text.length() < 1500) text.append(node.getText()).append(" | ");
            for (int i = 0; i < node.getChildCount() && text.length() < 1500; i++) collectVisibleText(node.getChild(i), text);
        } finally { node.recycle(); }
    }
    private static boolean tapText(Instrumentation driver, String text) throws Exception {
        android.graphics.Rect bounds = new android.graphics.Rect();
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        boolean ready = false;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            bounds.setEmpty();
            if (textBounds(driver.getUiAutomation().getRootInActiveWindow(), text, bounds) && !bounds.isEmpty()) { ready = true; break; }
            Thread.sleep(100);
        }
        if (!ready) return false;
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        try { driver.sendPointerSync(press); driver.sendPointerSync(release); } finally { press.recycle(); release.recycle(); }
        return true;
    }
    private static boolean textBounds(AccessibilityNodeInfo node, String text, android.graphics.Rect bounds) {
        if (node == null) return false;
        try {
            if (node.isVisibleToUser() && node.getText() != null && node.getText().toString().equals(text)) { node.getBoundsInScreen(bounds); return true; }
            for (int i = 0; i < node.getChildCount(); i++) if (textBounds(node.getChild(i), text, bounds)) return true;
            return false;
        } finally { node.recycle(); }
    }
    private static boolean clickText(Instrumentation driver, String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (clickNode(driver.getUiAutomation().getRootInActiveWindow(), text)) return true;
            Thread.sleep(100);
        }
        return false;
    }
    private static boolean clickNode(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        try {
            if (node.getText() != null && node.getText().toString().equalsIgnoreCase(text)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            for (int i = 0; i < node.getChildCount(); i++) if (clickNode(node.getChild(i), text)) return true;
            return false;
        } finally { node.recycle(); }
    }
    private static WebResourceRequest resource(String url, boolean main, boolean gesture) {
        return new WebResourceRequest() {
            public Uri getUrl() { return Uri.parse(url); }
            public boolean isForMainFrame() { return main; }
            public boolean isRedirect() { return false; }
            public boolean hasGesture() { return gesture; }
            public String getMethod() { return "GET"; }
            public Map<String, String> getRequestHeaders() { return java.util.Collections.emptyMap(); }
        };
    }
    private static boolean hasText(Instrumentation driver, String expected) {
        AccessibilityNodeInfo root = driver.getUiAutomation().getRootInActiveWindow();
        return containsText(root, expected);
    }
    private static boolean containsText(AccessibilityNodeInfo node, String expected) {
        if (node == null) return false;
        try {
            if (node.getText() != null && node.getText().toString().contains(expected)) return true;
            for (int i = 0; i < node.getChildCount(); i++) if (containsText(node.getChild(i), expected)) return true;
            return false;
        } finally { node.recycle(); }
    }
    private static JSONObject request(String origin, String token, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-GridLens-Token", token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            require(connection.getResponseCode() == 200, "Direct PC fixture API request failed");
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(NodeRuntime.readBounded(input, 512 * 1024), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private DirectPcIpVerification() { }
}
