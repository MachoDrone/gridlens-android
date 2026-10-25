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
            require(!reopened.suspendCredentialProfile(target), "An old approval suspended the newly approved shared credential target");
            reopened.restrictMonitoring(target, address, true, true, "identity-changed", retry);
            require(!reopened.monitoringTargets().getJSONObject(0).getBoolean("paused"), "Late prior-authorization receipt revoked the replacement authorization");
            JSONObject paused = new JSONObject(approvedAgain.toString()).put("approved", false).put("paused", true);
            reopened.saveMonitoringTarget(paused);
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
            reopened.forgetCredentials();
            require(!reopened.suspendCredentialProfile(approvedAgain), "A forgotten target accepted an old credential failure");
            reopened.restrictMonitoring(approvedAgain, address, true, true, "identity-changed", retry);
            require(reopened.monitoringTargets().length() == 0 && reopened.credentialProfiles().length() == 0, "A late receipt recreated forgotten monitoring authority");
            require(reopened.rewardsRetryAtMs() == retry, "Forgetting credentials bypassed the provider delay");
            try { reopened.saveMonitoringTarget(approvedAgain); throw new AssertionError("An approved target was saved without its credential"); }
            catch (java.security.GeneralSecurityException expected) { }
        } finally {
            pair.dispose(); store.forgetCredentials();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) require(file.delete(), "Monitoring fixture cleanup failed");
            require(directory.delete(), "Monitoring fixture directory cleanup failed");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private MonitoringVerification() { }
}
