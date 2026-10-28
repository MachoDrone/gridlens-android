package com.gridlens.android;

import android.app.Instrumentation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Real encrypted Android store and current API associations; disposable SSH identity only. */
final class AutomaticPcIpPolicyVerification {
    static void verify(Instrumentation driver, MainActivity activity, String origin, String token,
            String fleet, JSONObject observedTarget) throws Exception {
        String namespace = "automatic-pc-ip-policy";
        File directory = new File(activity.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "Unused automatic IP policy fixture namespace required");
        DiscoveryStore store = new DiscoveryStore(activity, namespace);
        MonitoringController monitoring = new MonitoringController(activity, store, origin, token);
        com.jcraft.jsch.KeyPair changed = null;
        try {
            SshDiscovery.Identity identity = new SshDiscovery.Identity(observedTarget.getString("keyBase64"), observedTarget.getString("algorithm"));
            DiscoveryPolicy.Candidate endpoint = new DiscoveryPolicy.Candidate(observedTarget.getString("address"), observedTarget.getInt("port"), "");
            String profile = store.saveProfile(null, "Generated policy fixture", new JSONArray().put("10.0.2.2/32"), new JSONArray().put(endpoint.port));
            store.trust(profile, endpoint.endpointKey(), identity.keyBase64);
            String credential = store.saveCredential(new JSONObject().put("name", "Generated policy credential").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), identity.keyBase64);
            JSONObject base = new JSONObject(observedTarget.toString()).put("profileId", profile).put("credentialId", credential);
            try (SshDiscovery.Credential secret = DiscoveryController.decodeCredential(store.credential(credential));
                    DiscoveryNetwork network = DiscoveryNetwork.capture(activity)) {
                SshDiscovery.BatchRequest selected = new SshDiscovery.BatchRequest(endpoint, identity, secret, null);
                SshDiscovery.Result authenticated = SshDiscovery.inventory(network, endpoint, identity, secret, null);
                require(authenticated.status == SshDiscovery.Status.SUCCESS && authenticated.addresses.size() == 3,
                        "Policy fixture did not authenticate the three-node PC");
                InspectionSelectionPolicyVerification.verify(driver, activity, origin, token, fleet, observedTarget, authenticated);
                List<SshDiscovery.BatchRequest> requests = List.of(selected);
                Map<String, SshDiscovery.Result> results = Map.of(endpoint.endpointKey(), authenticated);
                Map<String, String> credentials = Map.of(endpoint.endpointKey(), credential);

                seed(store, base);
                String before = store.monitoringTargets().toString();
                MonitoringController.PcIpEnrollmentReport healthy = monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true);
                require(healthy.enabled == 0 && healthy.unchanged == 1 && before.equals(store.monitoringTargets().toString()),
                        "Ordinary discovery rewrote healthy PC-only authority");

                for (String status : new String[]{"paused", "credentials-required", "identity-changed", "authorization-required"}) {
                    JSONObject restricted = new JSONObject(base.toString()).put("paused", true).put("status", status)
                            .put("approved", !status.equals("authorization-required"));
                    seed(store, restricted); before = store.monitoringTargets().toString();
                    MonitoringController.PcIpEnrollmentReport denied = monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true);
                    require(denied.enabled == 0 && denied.unchanged == 0 && before.equals(store.monitoringTargets().toString()),
                            "Ordinary discovery erased existing " + status + " restriction");
                }

                JSONObject full = new JSONObject(base.toString()).put("paused", false).put("status", "active")
                        .put("rewardsAuthorized", true).put("rewardsVerified", new JSONArray().put(authenticated.addresses.get(0)))
                        .put("rewardsSuspended", new JSONArray().put(authenticated.addresses.get(1)))
                        .put("rewardsRetryAtMs", System.currentTimeMillis() + 3600000).put("networkRetryAtMs", System.currentTimeMillis() + 3600000)
                        .put("gpuCursors", new JSONObject().put(authenticated.addresses.get(0), 1234));
                seed(store, full); before = store.monitoringTargets().toString();
                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true).unchanged == 1
                        && before.equals(store.monitoringTargets().toString()), "Automatic IP changed existing wallet suspension, source cursors or provider waits");

                JSONObject legacy = new JSONObject(base.toString()).put("approved", false).put("paused", false)
                        .put("status", "authorization-required").put("lastSetupAttemptAtMs", 0).put("rewardsVerified", new JSONArray())
                        .put("rewardsSuspended", new JSONArray());
                for (String field : new String[]{"gpuCursors", "lastGpuAttemptAtMs", "lastRewardsAttemptAtMs", "lastNetworkAttemptAtMs",
                        "lastConnectionCheckAtMs", "retryAtMs", "rewardsRetryAtMs", "networkRetryAtMs", "recoveryGeneration"}) legacy.remove(field);
                seed(store, legacy);
                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true).enabled == 1,
                        "Clean never-approved legacy inspection did not receive explicit ordinary PC-IP approval");
                JSONObject upgraded = store.monitoringTargets().getJSONObject(0);
                require(upgraded.getBoolean("approved") && upgraded.getBoolean("paused") && !upgraded.getBoolean("rewardsAuthorized")
                        && !upgraded.getBoolean("setupPending") && !upgraded.getBoolean("sudoApproved"), "Legacy conversion granted additional source authority");

                for (Object malformed : new Object[]{0.5, "0", JSONObject.NULL}) {
                    JSONObject ambiguous = new JSONObject(legacy.toString()).put("lastSetupAttemptAtMs", malformed);
                    seed(store, ambiguous); before = store.monitoringTargets().toString();
                    require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true).enabled == 0
                            && before.equals(store.monitoringTargets().toString()), "Ambiguous legacy setup timestamp granted automatic access");
                }
                JSONObject manualDuplicate = new JSONObject(legacy.toString()).put("id", java.util.UUID.randomUUID().toString())
                        .put("paused", true).put("status", "paused");
                for (boolean reversed : new boolean[]{false, true}) {
                    if (reversed) seed(store, manualDuplicate, legacy); else seed(store, legacy, manualDuplicate);
                    before = store.monitoringTargets().toString();
                    require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true).enabled == 0
                            && before.equals(store.monitoringTargets().toString()), "Pristine legacy record bypassed a second manual restriction for the same PC");
                }

                seed(store);
                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> false).enabled == 0
                        && store.monitoringTargets().length() == 0, "Cancelled native consent granted automatic access");
                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, Collections.emptyMap(), () -> true).enabled == 0
                        && store.monitoringTargets().length() == 0, "Unremembered credentials became persistent authority");
                // The API supplies association truth. A generated identity may authenticate
                // its fixture, but it is absent from this fleet and cannot be enrolled.
                changed = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
                SshDiscovery.Identity otherIdentity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(changed.getPublicKeyBlob()), "ssh-rsa");
                SshDiscovery.BatchRequest other = new SshDiscovery.BatchRequest(endpoint, otherIdentity, secret, null);
                SshDiscovery.Result unassociated = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, authenticated.addresses, 0,
                        PcMetadata.observed(otherIdentity, endpoint.address, "unassociated-fixture", endpoint.address, System.currentTimeMillis()));
                require(monitoring.registerAutomaticPcIp(fleet, profile, List.of(other), Map.of(endpoint.endpointKey(), unassociated), credentials, () -> true).enabled == 0
                        && store.monitoringTargets().length() == 0, "PC without current saved-fleet association was enrolled");
                SshDiscovery.Result rejectedIdentity = SshDiscovery.inventory(network, endpoint, otherIdentity, secret, null);
                require(rejectedIdentity.status == SshDiscovery.Status.IDENTITY_CHANGED, "Generated wrong SSH pin was not actually rejected");
                require(monitoring.registerAutomaticPcIp(fleet, profile, List.of(other), Map.of(endpoint.endpointKey(), rejectedIdentity), credentials, () -> true).enabled == 0
                        && store.monitoringTargets().length() == 0, "Rejected SSH identity gained automatic authority");

                // A real failed SSH login must remain authoritative for all saved
                // targets sharing that exact generated credential profile.
                String badId = store.saveCredential(new JSONObject().put("name", "Generated rejected credential").put("username", "fixture")
                        .put("password", "deliberately-invalid-fixture-password").put("key", "").put("passphrase", ""), null);
                try (SshDiscovery.Credential bad = DiscoveryController.decodeCredential(store.credential(badId))) {
                    SshDiscovery.Result rejected = SshDiscovery.inventory(network, endpoint, identity, bad, null);
                    require(rejected.status == SshDiscovery.Status.AUTH_FAILED, "Generated bad SSH password was not actually rejected");
                    JSONObject first = new JSONObject(base.toString()).put("credentialId", badId).put("paused", false).put("status", "active");
                    JSONObject second = new JSONObject(first.toString()).put("id", java.util.UUID.randomUUID().toString()).put("fleetId", java.util.UUID.randomUUID().toString());
                    seed(store, first, second);
                    SshDiscovery.BatchRequest badRequest = new SshDiscovery.BatchRequest(endpoint, identity, bad, null);
                    require(monitoring.registerAutomaticPcIp(fleet, profile, List.of(badRequest), Map.of(endpoint.endpointKey(), rejected),
                            Map.of(endpoint.endpointKey(), badId), () -> true).enabled == 0, "Rejected password gained automatic authority");
                    JSONArray stopped = store.monitoringTargets();
                    require(stopped.length() == 2, "Shared credential rejection discarded targets");
                    for (int i = 0; i < stopped.length(); i++) require(stopped.getJSONObject(i).getBoolean("paused")
                            && "credentials-required".equals(stopped.getJSONObject(i).getString("status")), "Shared rejected credential remained schedulable");
                    store.lock();
                    require(store.monitoringTargets().getJSONObject(1).getBoolean("paused"), "SSH rejection did not survive encrypted vault reload");
                }
            }
        } finally {
            driver.runOnMainSync(monitoring::destroy);
            if (changed != null) changed.dispose();
            store.forgetCredentials();
            File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
        }
    }
    private static void seed(DiscoveryStore store, JSONObject... targets) throws Exception {
        JSONArray values = new JSONArray(); for (JSONObject target : targets) values.put(target);
        java.lang.reflect.Method read = DiscoveryStore.class.getDeclaredMethod("credentials"); read.setAccessible(true);
        JSONObject value = new JSONObject(((JSONObject) read.invoke(store)).toString()).put("monitoringTargets", values);
        java.lang.reflect.Method write = DiscoveryStore.class.getDeclaredMethod("saveCredentials", JSONObject.class); write.setAccessible(true); write.invoke(store, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private AutomaticPcIpPolicyVerification() { }
}
