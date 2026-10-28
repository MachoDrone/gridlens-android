package com.gridlens.android;

import android.app.Instrumentation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host selection against the generated fixture's public fleet and an isolated encrypted vault. */
final class InspectionSelectionPolicyVerification {
    static void verify(Instrumentation driver, MainActivity activity, String origin, String token,
            String fleet, JSONObject observedTarget, SshDiscovery.Result authenticated) throws Exception {
        String namespace = "inspection-selection-policy";
        File directory = new File(activity.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "Unused selection policy fixture namespace required");
        DiscoveryStore store = new DiscoveryStore(activity, namespace);
        MonitoringController monitoring = new MonitoringController(activity, store, origin, token);
        com.jcraft.jsch.KeyPair otherKey = null;
        long connectionsBefore = DiscoveryNetwork.startedConnectionsForTesting();
        try {
            require(authenticated.addresses.size() == 3 && authenticated.bindings.size() == 3,
                    "Selection policy requires the generated three-node fixture");
            SshDiscovery.Identity identity = new SshDiscovery.Identity(observedTarget.getString("keyBase64"), observedTarget.getString("algorithm"));
            DiscoveryPolicy.Candidate endpoint = new DiscoveryPolicy.Candidate(observedTarget.getString("address"), observedTarget.getInt("port"), "");
            String profile = store.saveProfile(null, "Generated selection fixture", new JSONArray().put("10.0.2.2/32"), new JSONArray().put(endpoint.port));
            store.trust(profile, endpoint.endpointKey(), identity.keyBase64);
            String credentialId = store.saveCredential(new JSONObject().put("name", "Generated selection credential").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), null);
            String selectedAddress = savedFixtureAddress(origin, token, fleet, authenticated.addresses);
            Map<String, SshDiscovery.Result> results = Map.of(endpoint.endpointKey(), authenticated);
            Map<String, String> credentials = Map.of(endpoint.endpointKey(), credentialId);
            InspectionSelection selected = InspectionSelection.from(results, List.of(selectedAddress));
            InspectionSelection empty = InspectionSelection.from(results, Collections.emptyList());
            try (SshDiscovery.Credential secret = DiscoveryController.decodeCredential(store.credential(credentialId))) {
                SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(endpoint, identity, secret, null);
                List<SshDiscovery.BatchRequest> requests = List.of(request);

                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true, empty).enabled == 0,
                        "Empty choice granted automatic IP access");
                require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, empty).targets.isEmpty()
                        && store.monitoringTargets().length() == 0, "Empty host selection was interpreted as unrestricted repair");
                require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, selected, () -> false).targets.isEmpty()
                        && store.monitoringTargets().length() == 0, "Cancelled selection granted monitoring access");
                java.util.concurrent.atomic.AtomicInteger consentChecks = new java.util.concurrent.atomic.AtomicInteger();
                require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, selected,
                        () -> consentChecks.incrementAndGet() <= 2).targets.isEmpty() && store.monitoringTargets().length() == 0,
                        "Cancellation during the atomic approval check was ignored");
                int credentialCount = store.credentialProfiles().length();
                consentChecks.set(0);
                require(monitoring.register(fleet, profile, requests, results, Collections.emptyMap(), Collections.emptyMap(), true, selected,
                        () -> consentChecks.incrementAndGet() <= 2).targets.isEmpty() && store.credentialProfiles().length() == credentialCount,
                        "Cancellation saved newly remembered credentials");

                // The legacy overload is reserved for explicit saved-PC repair and retains its full scope.
                List<JSONObject> repaired = monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true);
                require(repaired.size() == 1 && repaired.get(0).getJSONArray("bindings").length() == 3,
                        "Explicit repair lost its existing full-PC behavior");
                seed(store);

                MonitoringController.MonitoringRegistrationReport fresh = monitoring.register(fleet, profile, requests, results,
                        credentials, Collections.emptyMap(), true, selected);
                require(fresh.targets.size() == 1 && fresh.deferred.isEmpty(), "Selected new host did not receive its approved setup");
                JSONObject one = store.monitoringTargets().getJSONObject(0);
                require(one.getJSONArray("bindings").length() == 1
                        && selectedAddress.equals(one.getJSONArray("bindings").getJSONObject(0).getString("address")),
                        "Unselected nodes gained monitoring bindings");
                require(one.getBoolean("setupPending") && one.getBoolean("approved"), "Selected host setup was not retained");
                verifyFailureBeforeHostChoice(store, monitoring, endpoint, identity, one);

                JSONObject healthyOne = new JSONObject(one.toString()).put("setupPending", false).put("paused", false).put("status", "active")
                        .put("gpuCursors", new JSONObject().put(selectedAddress, 1234)).put("rewardsVerified", new JSONArray().put(selectedAddress))
                        .put("lastSetupAttemptAtMs", 123456L).put("retryAtMs", System.currentTimeMillis() + 3600000)
                        .put("rewardsRetryAtMs", System.currentTimeMillis() + 3600000).put("networkRetryAtMs", System.currentTimeMillis() + 3600000);
                seed(store, healthyOne);
                require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, selected).targets.size() == 1,
                        "Fully selected unchanged node could not retain its monitoring");
                JSONObject retained = store.monitoringTargets().getJSONObject(0);
                for (String field : new String[]{"gpuCursors", "rewardsVerified", "lastSetupAttemptAtMs", "retryAtMs", "rewardsRetryAtMs", "networkRetryAtMs"})
                    require(healthyOne.get(field).toString().equals(retained.get(field).toString()), "Scoped registration reset retained " + field);

                SshDiscovery.Result incomplete = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of(selectedAddress), 1,
                        authenticated.pcMetadata, authenticated.gpuCollectorStatus, Collections.emptyList());
                Map<String, SshDiscovery.Result> incompleteResults = Map.of(endpoint.endpointKey(), incomplete);
                seed(store, healthyOne);
                String beforeIncomplete = store.monitoringTargets().toString();
                MonitoringController.MonitoringRegistrationReport unresolved = monitoring.register(fleet, profile, requests, incompleteResults,
                        credentials, Collections.emptyMap(), true, InspectionSelection.from(incompleteResults, List.of(selectedAddress)));
                require(unresolved.targets.isEmpty() && unresolved.deferred.size() == 1
                        && unresolved.deferred.get(0).reason.contains("binding") && beforeIncomplete.equals(store.monitoringTargets().toString()),
                        "Incomplete current inventory discarded an already approved binding");

                JSONObject complete = new JSONObject(one.toString()).put("bindings", bindings(authenticated.bindings))
                        .put("setupPending", false).put("paused", false).put("status", "active")
                        .put("gpuCursors", new JSONObject().put(authenticated.addresses.get(1), 1234))
                        .put("rewardsRetryAtMs", System.currentTimeMillis() + 3600000);
                seed(store, complete);
                String before = store.monitoringTargets().toString();
                MonitoringController.MonitoringRegistrationReport partial = monitoring.register(fleet, profile, requests, results,
                        credentials, Collections.emptyMap(), true, selected);
                require(partial.targets.isEmpty() && partial.deferred.size() == 1 && before.equals(store.monitoringTargets().toString()),
                        "Partial rescan rewrote the PC-wide permissions, setup, bindings or cursor for excluded nodes");
                require(monitoring.registerAutomaticPcIp(fleet, profile, requests, results, credentials, () -> true, selected).unchanged == 1
                        && before.equals(store.monitoringTargets().toString()), "Selected IP enrollment changed healthy existing node monitoring");

                for (String status : new String[]{"paused", "identity-changed", "binding-changed"}) {
                    JSONObject restricted = new JSONObject(one.toString()).put("setupPending", false).put("paused", true).put("status", status);
                    seed(store, restricted); before = store.monitoringTargets().toString();
                    require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, selected).targets.isEmpty()
                            && before.equals(store.monitoringTargets().toString()), "Selected discovery erased a saved " + status + " restriction");
                }
                JSONObject deniedSource = new JSONObject(one.toString()).put("setupPending", false).put("paused", false).put("status", "active")
                        .put("rewardsSuspended", new JSONArray().put(selectedAddress)).put("rewardsAuthorized", false);
                seed(store, deniedSource); before = store.monitoringTargets().toString();
                require(monitoring.register(fleet, profile, requests, results, credentials, Collections.emptyMap(), true, selected).targets.isEmpty()
                        && before.equals(store.monitoringTargets().toString()), "Selected discovery cleared a wallet denial");

                // A conflicting public address is still saveable, but cannot identify an authorized PC.
                otherKey = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
                SshDiscovery.Identity other = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(otherKey.getPublicKeyBlob()), "ssh-rsa");
                DiscoveryPolicy.Candidate secondEndpoint = new DiscoveryPolicy.Candidate("192.0.2.89", 22, "generated-only");
                SshDiscovery.BatchRequest secondRequest = new SshDiscovery.BatchRequest(secondEndpoint, other, secret, null);
                SshDiscovery.Result conflicting = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of(selectedAddress), 0,
                        PcMetadata.observed(other, secondEndpoint.address, "generated-only", secondEndpoint.address, System.currentTimeMillis()));
                Map<String, SshDiscovery.Result> conflictingResults = new LinkedHashMap<>(results);
                conflictingResults.put(secondEndpoint.endpointKey(), conflicting);
                InspectionSelection ambiguous = InspectionSelection.from(conflictingResults, List.of(selectedAddress));
                seed(store);
                require(monitoring.registerAutomaticPcIp(fleet, profile, List.of(request, secondRequest), conflictingResults, credentials,
                        () -> true, ambiguous).enabled == 0, "Conflicting PC association gained automatic IP permission");
                require(monitoring.register(fleet, profile, List.of(request, secondRequest), conflictingResults, credentials,
                        Collections.emptyMap(), true, ambiguous).targets.isEmpty() && store.monitoringTargets().length() == 0,
                        "Conflicting PC association gained node permissions");

                // Synthetic AUTH_FAILED evidence deliberately belongs to a PC with no selectable hosts.
                Map<String, SshDiscovery.Result> failedResults = new LinkedHashMap<>(results);
                failedResults.put(secondEndpoint.endpointKey(), SshDiscovery.Result.of(SshDiscovery.Status.AUTH_FAILED));
                Map<String, String> sharedCredentials = Map.of(endpoint.endpointKey(), credentialId, secondEndpoint.endpointKey(), credentialId);
                InspectionSelection excludingFailure = InspectionSelection.from(failedResults, List.of(selectedAddress));
                seed(store, new JSONObject(one.toString()).put("setupPending", false).put("paused", false).put("status", "active"));
                require(monitoring.registerAutomaticPcIp(fleet, profile, List.of(request, secondRequest), failedResults, sharedCredentials,
                        () -> true, excludingFailure).enabled == 0, "Excluded failed PC lost its shared credential rejection");
                require(monitoring.register(fleet, profile, List.of(request, secondRequest), failedResults, sharedCredentials,
                        Collections.emptyMap(), true, excludingFailure).targets.isEmpty(), "Full monitoring reused a rejected shared credential");
                store.lock();
                JSONObject rejected = store.monitoringTargets().getJSONObject(0);
                require(rejected.getBoolean("paused") && "credentials-required".equals(rejected.getString("status")),
                        "Selection lost the durable rejection of a shared credential");
            }
            require(DiscoveryNetwork.startedConnectionsForTesting() == connectionsBefore,
                    "Selection registration or deferred setup opened an SSH connection");
        } finally {
            driver.runOnMainSync(monitoring::destroy);
            if (otherKey != null) otherKey.dispose();
            store.forgetCredentials();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) require(file.delete(), "Selection fixture file cleanup failed");
            require(directory.delete(), "Selection fixture directory cleanup failed");
        }
    }

    private static JSONArray bindings(List<NodeBinding> bindings) throws Exception {
        JSONArray values = new JSONArray();
        for (NodeBinding binding : bindings) values.put(new JSONObject().put("runtime", binding.runtime)
                .put("wrapperId", binding.wrapperId == null ? JSONObject.NULL : binding.wrapperId)
                .put("nodeContainerId", binding.nodeContainerId).put("address", binding.address));
        return values;
    }
    private static void verifyFailureBeforeHostChoice(DiscoveryStore store, MonitoringController monitoring,
            DiscoveryPolicy.Candidate endpoint, SshDiscovery.Identity identity, JSONObject template) throws Exception {
        JSONObject profile = new JSONObject().put("name", "Generated pre-save rejection").put("username", "fixture")
                .put("password", "generated-secret-before-cancel").put("key", "").put("passphrase", "");
        String rejectedId = store.saveCredential(profile, null);
        JSONObject existing = new JSONObject(template.toString()).put("credentialId", rejectedId)
                .put("setupPending", false).put("paused", false).put("status", "active");
        seed(store, existing);
        try (SshDiscovery.Credential requestSecret = DiscoveryController.decodeCredential(store.credential(rejectedId))) {
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(endpoint, identity, requestSecret, null);
            MonitoringController.InspectionCredentialEvidence evidence = monitoring.captureInspectionCredentials(List.of(request),
                    Map.of(endpoint.endpointKey(), rejectedId));
            // Cancelling the new host chooser destroys live buffers. No fleet save or registration follows.
            requestSecret.close();
            monitoring.recordInspectionFailure(evidence, endpoint.endpointKey(), SshDiscovery.Status.AUTH_FAILED);
            store.lock();
            JSONObject target = store.monitoringTargets().getJSONObject(0);
            require(target.getBoolean("paused") && "credentials-required".equals(target.getString("status")),
                    "Cancelling before host save discarded a real shared credential rejection");
        }

        String changedId = store.saveCredential(new JSONObject(profile.toString()).put("name", "Generated changed profile"), null);
        JSONObject changedTarget = new JSONObject(existing.toString()).put("credentialId", changedId).put("paused", false).put("status", "active");
        seed(store, changedTarget);
        try (SshDiscovery.Credential requestSecret = DiscoveryController.decodeCredential(store.credential(changedId))) {
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(endpoint, identity, requestSecret, null);
            MonitoringController.InspectionCredentialEvidence evidence = monitoring.captureInspectionCredentials(List.of(request),
                    Map.of(endpoint.endpointKey(), changedId));
            String before = store.monitoringTargets().toString();
            monitoring.recordInspectionFailure(evidence, endpoint.endpointKey(), SshDiscovery.Status.CANCELLED);
            require(before.equals(store.monitoringTargets().toString()), "Cancellation was treated as authentication rejection");
            // Exercise the durable snapshot comparison even if a future profile editor changes bytes in-place.
            replaceCredential(store, changedId, new JSONObject(store.credential(changedId).toString()).put("password", "generated-replacement"));
            requestSecret.close();
            monitoring.recordInspectionFailure(evidence, endpoint.endpointKey(), SshDiscovery.Status.AUTH_FAILED);
            store.lock();
            require(before.equals(store.monitoringTargets().toString()), "Old rejection suspended a changed saved credential");
        }
        seed(store);
    }
    private static void replaceCredential(DiscoveryStore store, String id, JSONObject replacement) throws Exception {
        java.lang.reflect.Method read = DiscoveryStore.class.getDeclaredMethod("credentials"); read.setAccessible(true);
        JSONObject value = new JSONObject(((JSONObject) read.invoke(store)).toString());
        JSONArray profiles = value.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) if (id.equals(profiles.getJSONObject(i).getString("id"))) profiles.put(i, replacement);
        java.lang.reflect.Method write = DiscoveryStore.class.getDeclaredMethod("saveCredentials", JSONObject.class); write.setAccessible(true); write.invoke(store, value);
    }
    private static String savedFixtureAddress(String origin, String token, String fleet, List<String> candidates) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + "/mobile/fleets").openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setRequestProperty("X-GridLens-Token", token);
        try (InputStream input = connection.getInputStream()) {
            JSONArray fleets = new JSONObject(new String(NodeRuntime.readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8)).getJSONArray("fleets");
            for (int i = 0; i < fleets.length(); i++) {
                JSONObject value = fleets.getJSONObject(i);
                if (!fleet.equals(value.getString("id"))) continue;
                JSONArray hosts = value.getJSONArray("hosts");
                for (int j = 0; j < hosts.length(); j++) {
                    String address = hosts.getJSONObject(j).getString("address");
                    if (candidates.contains(address)) return address;
                }
            }
            throw new AssertionError("Saved synthetic fleet contains no inspected host");
        } finally { connection.disconnect(); }
    }
    private static void seed(DiscoveryStore store, JSONObject... targets) throws Exception {
        JSONArray values = new JSONArray(); for (JSONObject target : targets) values.put(target);
        java.lang.reflect.Method read = DiscoveryStore.class.getDeclaredMethod("credentials"); read.setAccessible(true);
        JSONObject value = new JSONObject(((JSONObject) read.invoke(store)).toString()).put("monitoringTargets", values);
        java.lang.reflect.Method write = DiscoveryStore.class.getDeclaredMethod("saveCredentials", JSONObject.class); write.setAccessible(true); write.invoke(store, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private InspectionSelectionPolicyVerification() { }
}
