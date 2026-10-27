package com.gridlens.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Uses only generated SSH identity and an isolated Keystore/vault namespace. No network calls. */
final class MonitoringVerification {
    static void verify(Context context) throws Exception {
        String namespace = "monitoring-verification";
        File directory = new File(context.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "Monitoring instrumentation requires an unused fixture namespace");
        DiscoveryStore store = new DiscoveryStore(context, namespace);
        SshDiscovery.configureCryptoForAndroid();
        com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        try {
            String address = "1".repeat(32);
            SshDiscovery.Identity identity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(pair.getPublicKeyBlob()), "ssh-rsa");
            String profile = store.saveProfile(null, "Synthetic monitoring", new JSONArray().put("192.0.2.0/24"), new JSONArray().put(22));
            store.trust(profile, "[192.0.2.12]:22", identity.keyBase64);
            String credential = store.saveCredential(new JSONObject().put("name", "Monitoring fixture").put("username", "fixture")
                    .put("password", "isolated-monitoring-password").put("key", "").put("passphrase", ""), identity.keyBase64);
            JSONObject target = new JSONObject().put("id", UUID.randomUUID().toString()).put("fleetId", UUID.randomUUID().toString())
                    .put("profileId", profile).put("address", "192.0.2.12").put("port", 22).put("keyBase64", identity.keyBase64)
                    .put("algorithm", identity.algorithm).put("pcId", MonitoringController.pcId(identity)).put("credentialId", credential)
                    .put("approved", true).put("paused", false).put("rewardsAuthorized", true).put("authorizationId", UUID.randomUUID().toString())
                    .put("setupPending", true).put("rewardsVerified", new JSONArray().put(address)).put("rewardsSuspended", new JSONArray())
                    .put("bindings", new JSONArray().put(new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                            .put("nodeContainerId", "a".repeat(64)).put("address", address)));
            store.saveMonitoringTarget(target);
            NodeBinding node = new NodeBinding("docker", null, "a".repeat(64), address);
            SshDiscovery.MonitoringResult publicRead = new SshDiscovery.MonitoringResult(node, null, null, "active", false, 0,
                    new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, null, null).json());
            require(MonitoringController.permittedObservation(store, target, publicRead), "Approved native delivery was blocked");
            verifySourceIsolation(target.getString("pcId"), node);
            JSONObject sibling = new JSONObject(target.toString()).put("id", UUID.randomUUID().toString()).put("fleetId", UUID.randomUUID().toString())
                    .put("address", "192.0.2.13");
            store.saveMonitoringTarget(sibling);
            String otherCredential = store.saveCredential(new JSONObject().put("name", "Independent fixture").put("username", "fixture")
                    .put("password", "different-isolated-password").put("key", "").put("passphrase", ""), null);
            JSONObject independent = new JSONObject(target.toString()).put("id", UUID.randomUUID().toString()).put("credentialId", otherCredential)
                    .put("fleetId", UUID.randomUUID().toString()).put("address", "192.0.2.14");
            store.saveMonitoringTarget(independent);
            String bytes = new String(Files.readAllBytes(new File(directory, "credentials.aesgcm").toPath()), StandardCharsets.UTF_8);
            require(!bytes.contains("isolated-monitoring-password") && !bytes.contains(target.getString("id")), "Native monitoring plaintext persisted");
            DiscoveryStore reopened = new DiscoveryStore(context, namespace);
            require(reopened.monitoringTargets().getJSONObject(0).getBoolean("setupPending"), "Approved setup could not resume after reopening");
            require(reopened.suspendCredentialProfile(target), "Current SSH denial was not accepted");
            JSONArray sharedPause = reopened.monitoringTargets();
            require(sharedPause.getJSONObject(0).getBoolean("paused") && sharedPause.getJSONObject(1).getBoolean("paused"),
                    "Shared SSH credential denial left another PC eligible for password retries");
            require(!sharedPause.getJSONObject(2).getBoolean("paused"), "Unrelated credential profile was paused");
            reopened.saveMonitoringTarget(target); reopened.saveMonitoringTarget(sibling);
            long retry = System.currentTimeMillis() + 86_400_000L;
            reopened.restrictMonitoring(target, address, true, false, "authorization-required", retry);
            JSONObject denied = reopened.monitoringTargets().getJSONObject(0);
            require(!denied.getBoolean("rewardsAuthorized") && denied.getJSONArray("rewardsVerified").length() == 0,
                    "Authorization denial did not suspend the node independently of upload");
            require(!denied.getBoolean("paused"), "Rewards denial unnecessarily paused GPU reads");
            require(reopened.rewardsRetryAtMs() == retry, "Provider delay was not persisted");
            JSONObject approvedAgain = new JSONObject(target.toString()).put("authorizationId", UUID.randomUUID().toString());
            reopened.saveMonitoringTarget(approvedAgain);
            require(!MonitoringController.permittedObservation(reopened, target, publicRead), "Late source delivered across replacement authorization");
            require(MonitoringController.permittedObservation(reopened, approvedAgain, publicRead), "Replacement authorization blocked its own source");
            require(!reopened.suspendCredentialProfile(target), "An old approval suspended the newly approved shared credential target");
            reopened.restrictMonitoring(target, address, true, true, "identity-changed", retry);
            require(!reopened.monitoringTargets().getJSONObject(0).getBoolean("paused"), "Late prior-authorization receipt revoked the replacement authorization");
            JSONObject paused = new JSONObject(approvedAgain.toString()).put("approved", false).put("paused", true);
            reopened.saveMonitoringTarget(paused);
            require(!MonitoringController.permittedObservation(reopened, approvedAgain, publicRead), "Revoked permission delivered a successful source");
            reopened.restrictMonitoring(approvedAgain, address, true, false, "authorization-required", retry);
            require(!reopened.monitoringTargets().getJSONObject(0).getBoolean("approved"), "A late receipt re-enabled a paused target");
            JSONObject live = new JSONObject(approvedAgain.toString()).put("authorizationId", UUID.randomUUID().toString())
                    .put("lastRewardsAttemptAtMs", 1234).put("rewardsRetryAtMs", retry).put("rewardsSuspended", new JSONArray().put(address))
                    .put("gpuCursors", new JSONObject().put(address, 9000));
            reopened.saveMonitoringTarget(live);
            JSONObject alias = new JSONObject(live.toString()).put("id", UUID.randomUUID().toString()).put("authorizationId", UUID.randomUUID().toString())
                    .put("address", "198.51.100.12").put("lastRewardsAttemptAtMs", 0).put("rewardsRetryAtMs", 0)
                    .put("rewardsSuspended", new JSONArray()).put("gpuCursors", new JSONObject());
            reopened.saveMonitoringTarget(alias);
            JSONArray aliases = reopened.monitoringTargets(); int activeAliases = 0; JSONObject replacement = null;
            for (int i = 0; i < aliases.length(); i++) {
                JSONObject value = aliases.getJSONObject(i);
                if (value.getString("fleetId").equals(live.getString("fleetId")) && value.optBoolean("approved")) { activeAliases++; replacement = value; }
            }
            require(activeAliases == 1 && replacement != null, "Wi-Fi/VPN aliases both remained approved for one PC");
            require(replacement.getLong("lastRewardsAttemptAtMs") == 1234 && replacement.getLong("rewardsRetryAtMs") == retry,
                    "Changing endpoint reset request limits");
            require(replacement.getJSONArray("rewardsSuspended").length() == 1 && replacement.getJSONObject("gpuCursors").getLong(address) == 9000,
                    "Changing endpoint lost denied authorization or acknowledged GPU history");
            try { reopened.saveMonitoringTarget(live); throw new AssertionError("Late polling restored a replaced endpoint"); }
            catch (SecurityException expected) { }
            JSONObject recoveryBeforeForget = verifyConnectionRecovery(reopened, target, address, retry);
            long preservedRetry = reopened.rewardsRetryAtMs();
            reopened.forgetCredentials();
            require(!MonitoringController.permittedObservation(reopened, approvedAgain, publicRead), "Forgotten credentials delivered a late source");
            require(reopened.resumeVerifiedConnections(new JSONArray().put(recoveryBeforeForget), () -> true).length() == 0,
                    "Recovery recreated a forgotten credential/permission");
            require(!reopened.suspendCredentialProfile(approvedAgain), "A forgotten target accepted an old credential failure");
            reopened.restrictMonitoring(approvedAgain, address, true, true, "identity-changed", retry);
            require(reopened.monitoringTargets().length() == 0 && reopened.credentialProfiles().length() == 0, "A late receipt recreated forgotten monitoring authority");
            require(reopened.rewardsRetryAtMs() == preservedRetry, "Forgetting credentials bypassed the provider delay");
            try { reopened.saveMonitoringTarget(approvedAgain); throw new AssertionError("An approved target was saved without its credential"); }
            catch (java.security.GeneralSecurityException expected) { }
        } finally {
            pair.dispose(); store.forgetCredentials();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) require(file.delete(), "Monitoring fixture cleanup failed");
            require(directory.delete(), "Monitoring fixture directory cleanup failed");
        }
    }
    private static void verifySourceIsolation(String pcId, NodeBinding node) throws Exception {
        String metadata = MonitoringDelivery.update(pcId, 1000, node, "active");
        String injected = "{},\"rewards\":{\"amount\":\"999.000000\"}";
        for (String payload : new String[]{injected, "{malformed", "[]", "{} {}"}) {
            boolean rejected = false;
            try { MonitoringController.sourceUpdate(metadata, "gpu", payload); } catch (Exception expected) { rejected = true; }
            require(rejected, "Malformed GPU source crossed the native observation boundary");
        }
        JSONObject nested = MonitoringController.sourceUpdate(metadata, "gpu", "{\"rewards\":{\"amount\":\"999.000000\"}}");
        require(!nested.has("rewards") && nested.getJSONObject("gpu").has("rewards"), "GPU source escaped its nested object");
        java.util.List<String> accepted = new java.util.ArrayList<>();
        MonitoringDelivery.deliver(pcId, 1000, new SshDiscovery.MonitoringResult(node, "{\"status\":\"ok\"}", injected, "active", false, 0,
                        new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, null, null).json()), () -> true,
                (kind, envelope, payload) -> { MonitoringController.sourceUpdate(envelope, kind, payload); accepted.add(kind); });
        require(accepted.equals(java.util.Arrays.asList("network", "rewards")), "Malformed GPU report discarded another source");
    }
    private static JSONObject verifyConnectionRecovery(DiscoveryStore store, JSONObject template, String address, long retry) throws Exception {
        String other = "1".repeat(31) + "2";
        JSONObject paused = new JSONObject(template.toString()).put("id", UUID.randomUUID().toString()).put("fleetId", UUID.randomUUID().toString())
                .put("authorizationId", UUID.randomUUID().toString()).put("paused", true).put("status", "credentials-required")
                .put("lastRewardsAttemptAtMs", 1234).put("lastGpuAttemptAtMs", 2345).put("lastSetupAttemptAtMs", 3456)
                .put("rewardsRetryAtMs", retry).put("retryAtMs", retry).put("gpuCursors", new JSONObject().put(address, 9000))
                .put("rewardsVerified", new JSONArray().put(address)).put("rewardsSuspended", new JSONArray().put(other));
        paused.getJSONArray("bindings").put(new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                .put("nodeContainerId", "b".repeat(64)).put("address", other));
        store.saveMonitoringTarget(paused);
        require(store.resumeVerifiedConnections(new JSONArray().put(paused), () -> false).length() == 0, "Cancelled recovery changed saved authority");
        require(find(store, paused).getBoolean("paused"), "Cancelled recovery resumed monitoring");
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        require(store.resumeVerifiedConnections(new JSONArray().put(paused), () -> checks.incrementAndGet() == 1).length() == 0,
                "Cancellation before commit was ignored");
        JSONArray resumed = store.resumeVerifiedConnections(new JSONArray().put(paused), () -> true);
        require(resumed.length() == 1, "Verified credential pause did not resume");
        JSONObject current = resumed.getJSONObject(0);
        require(!current.getBoolean("paused") && "active".equals(current.getString("status")), "Connection did not become active");
        require(paused.getString("authorizationId").equals(current.getString("authorizationId")), "SSH recovery renewed source authorization");
        require(!current.optString("recoveryGeneration").isEmpty(), "Recovery receipt was not recorded");
        for (String key : new String[]{"approved", "rewardsAuthorized", "rewardsSuspended", "rewardsVerified", "rewardsRetryAtMs", "retryAtMs",
                "lastRewardsAttemptAtMs", "lastGpuAttemptAtMs", "lastSetupAttemptAtMs", "gpuCursors", "setupPending", "bindings", "credentialId"})
            require(paused.get(key).toString().equals(current.get(key).toString()), "SSH recovery changed source policy: " + key);
        // An already observed Nosana denial from the original approval still applies after SSH succeeds.
        store.restrictMonitoring(paused, address, true, false, "authorization-required", retry + 1000);
        JSONObject denied = find(store, paused);
        require(!denied.getBoolean("rewardsAuthorized") && denied.getJSONArray("rewardsSuspended").length() == 2,
                "Late source denial was lost after connection recovery");
        require(denied.getJSONArray("rewardsVerified").length() == 0 && denied.getLong("rewardsRetryAtMs") >= retry + 1000,
                "Late denial lost its suspension or retry deadline");
        denied.put("paused", true).put("status", "credentials-required"); store.saveMonitoringTarget(denied);
        require(store.resumeVerifiedConnections(new JSONArray().put(paused), () -> true).length() == 0, "Old recovery proof was replayed after a later pause");
        JSONObject replacement = new JSONObject(denied.toString()).put("authorizationId", UUID.randomUUID().toString());
        store.saveMonitoringTarget(replacement);
        require(store.resumeVerifiedConnections(new JSONArray().put(denied), () -> true).length() == 0, "Old proof replaced a newer explicit approval");
        JSONObject revoked = new JSONObject(replacement.toString()).put("approved", false); store.saveMonitoringTarget(revoked);
        require(store.resumeVerifiedConnections(new JSONArray().put(replacement), () -> true).length() == 0, "Explicit revocation was undone");
        for (String status : new String[]{"paused", "identity-changed", "binding-changed", "authorization-required"}) {
            JSONObject excluded = new JSONObject(replacement.toString()).put("status", status).put("authorizationId", UUID.randomUUID().toString());
            store.saveMonitoringTarget(excluded);
            require(store.resumeVerifiedConnections(new JSONArray().put(excluded), () -> true).length() == 0, "Unsupported pause was recovered: " + status);
        }
        JSONObject alias = new JSONObject(replacement.toString()).put("status", "credentials-required").put("approved", false).put("aliasReplaced", true);
        store.saveMonitoringTarget(alias);
        require(store.resumeVerifiedConnections(new JSONArray().put(alias), () -> true).length() == 0, "Replaced alias was revived");
        // Forget is performed by the outer fixture after these checks; test a removed target receipt here.
        store.removeMonitoringFleet(paused.getString("fleetId"));
        require(store.resumeVerifiedConnections(new JSONArray().put(replacement), () -> true).length() == 0, "Deleted target was recreated by recovery");
        store.saveMonitoringTarget(replacement);
        return replacement;
    }
    private static JSONObject find(DiscoveryStore store, JSONObject expected) throws Exception {
        JSONArray targets = store.monitoringTargets();
        for (int i = 0; i < targets.length(); i++) if (targets.getJSONObject(i).getString("id").equals(expected.getString("id"))) return targets.getJSONObject(i);
        throw new AssertionError("Fixture target missing");
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private MonitoringVerification() { }
}
