package com.gridlens.android;

import android.app.Instrumentation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Restores isolated legacy/malformed node state without modifying production acceptance rules. */
final class SavedTargetIpVerification {
    static void verify(Instrumentation instrumentation, MainActivity activity, String origin, String token) throws Exception {
        require(BuildConfig.DEBUG, "Debug fixture required");
        String namespace = "saved-target-ip-verification";
        File directory = new File(activity.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "Saved target fixture requires unused storage");
        DiscoveryStore store = new DiscoveryStore(activity, namespace);
        List<com.jcraft.jsch.KeyPair> keys = new ArrayList<>();
        List<JSONObject> targets = new ArrayList<>();
        List<String> pcIds = new ArrayList<>();
        JSONArray metadata = new JSONArray(), addresses = new JSONArray();
        String fleet = null;
        MonitoringController controller = null;
        AtomicInteger ipReads = new AtomicInteger(), nodeReads = new AtomicInteger();
        long at = System.currentTimeMillis();
        try {
            String profile = store.saveProfile(null, "Saved target fixture", new JSONArray().put("192.0.2.0/24"), new JSONArray().put(22));
            String credential = store.saveCredential(new JSONObject().put("name", "Saved target fixture").put("username", "fixture")
                    .put("password", "disposable-fixture-password").put("key", "").put("passphrase", ""), null);
            String symbols = "123456789ABCDEFGH";
            for (int index = 0; index < 10; index++) {
                com.jcraft.jsch.KeyPair key = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
                keys.add(key);
                SshDiscovery.Identity identity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(key.getPublicKeyBlob()), "ssh-rsa");
                String host = "1".repeat(31) + symbols.charAt(index), address = "192.0.2." + (40 + index), pc = MonitoringController.pcId(identity);
                pcIds.add(pc); store.trust(profile, "[" + address + "]:22", identity.keyBase64);
                addresses.put(host);
                metadata.put(new JSONObject().put("address", host).put("pcId", pc).put("pcName", "stored-pc-" + index)
                        .put("ipAddresses", new JSONArray().put(address)).put("observedAtMs", at));
                JSONObject binding = new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                        .put("nodeContainerId", "a".repeat(63) + index).put("address", host);
                targets.add(new JSONObject().put("id", UUID.randomUUID().toString()).put("profileId", profile)
                        .put("address", address).put("port", 22).put("keyBase64", identity.keyBase64).put("algorithm", identity.algorithm)
                        .put("pcId", pc).put("credentialId", credential).put("approved", true).put("paused", false)
                        .put("status", "unsupported").put("rewardsAuthorized", false).put("authorizationId", UUID.randomUUID().toString())
                        .put("lastGpuAttemptAtMs", 0).put("setupPending", false).put("bindings", new JSONArray().put(binding)));
            }
            // The first PC has three saved hosts even though its node-binding data is malformed.
            for (int index = 10; index < 12; index++) {
                String host = "1".repeat(31) + symbols.charAt(index); addresses.put(host);
                metadata.put(new JSONObject(metadata.getJSONObject(0).toString()).put("address", host));
            }
            fleet = request(origin, token, "/mobile/fleets", new JSONObject().put("op", "create").put("name", "Saved target IP fixture")
                    .put("addresses", addresses).put("pcMetadata", metadata)).getString("fleetId");
            verifyPcOnlyRegistration(activity, store, origin, token, fleet, profile, credential, targets.get(0));
            verifyReceiverParsing();
            for (JSONObject target : targets) { target.put("fleetId", fleet); store.saveMonitoringTarget(target); }
            targets.get(0).put("rewardsSuspended", new JSONArray().put(addresses.getString(0)))
                    .put("rewardsVerified", new JSONArray()).put("gpuCursors", new JSONObject().put(addresses.getString(0), 9000))
                    .put("rewardsRetryAtMs", at + 3_600_000);
            targets.get(0).remove("bindings");
            targets.get(1).getJSONArray("bindings").put(new JSONObject(targets.get(1).getJSONArray("bindings").getJSONObject(0).toString())
                    .put("nodeContainerId", "b".repeat(64)));
            targets.get(2).put("sudoPassword", "x".repeat(4097));
            targets.get(3).put("fleetId", UUID.randomUUID().toString()).remove("bindings");
            targets.get(4).put("pcId", "f".repeat(64)); // Does not match its saved SSH public key.
            targets.get(5).put("approved", false);
            targets.get(6).put("paused", true).put("status", "paused");
            targets.get(7).put("paused", true).put("status", "credentials-required");
            targets.get(8).put("paused", true).put("status", "identity-changed");
            // Seed only this disposable encrypted vault as if an older schema had been restored.
            // Production saveMonitoringTarget remains strict and cannot create these records.
            java.lang.reflect.Method read = DiscoveryStore.class.getDeclaredMethod("credentials"); read.setAccessible(true);
            JSONObject state = new JSONObject(((JSONObject) read.invoke(store)).toString());
            JSONArray restored = new JSONArray().put("invalid-target").put(new JSONObject().put("id", "not-a-target"));
            for (JSONObject target : targets) restored.put(target);
            state.put("monitoringTargets", restored);
            java.lang.reflect.Method save = DiscoveryStore.class.getDeclaredMethod("saveCredentials", JSONObject.class); save.setAccessible(true); save.invoke(store, state);
            DiscoveryStore reopened = new DiscoveryStore(activity, namespace);
            for (int index : new int[]{0, 1, 2}) {
                MonitoringController.validatePcTarget(targets.get(index));
                boolean rejected = false;
                try { MonitoringController.validateTarget(targets.get(index)); } catch (Exception invalidNodeState) { rejected = true; }
                require(rejected, "Node state validation was weakened");
            }
            controller = new MonitoringController(activity, reopened, origin, token, (target, login, permitted) -> {
                require(permitted.getAsBoolean(), "Invalid PC authority reached fixture transport");
                require(java.util.Arrays.asList(pcIds.get(0), pcIds.get(1), pcIds.get(2), pcIds.get(9)).contains(target.getString("pcId")),
                        "Unapproved, paused, changed identity or unrelated PC was collected");
                ipReads.incrementAndGet(); long observed = System.currentTimeMillis();
                return new SshDiscovery.NetworkResult("active", new PublicIpReader.Observation(observed, "8.8.8.8", observed, "192.168.1.42", observed));
            }, (target, enable, rewards, gpu) -> nodeReads.incrementAndGet());
            final MonitoringController running = controller; final String selectedFleet = fleet;
            instrumentation.runOnMainSync(() -> { running.selectedScenario("fleet:" + selectedFleet); running.resume(); });
            long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
            JSONArray networks = null;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                networks = request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks");
                if (networks.length() == 4) break;
                Thread.sleep(100);
            }
            require(networks != null && networks.length() == 4 && ipReads.get() == 4, "Malformed node state starved independently approved PC IP work");
            require(nodeReads.get() == 1, "Malformed node work ran, or a malformed sibling blocked the valid node worker");
            JSONObject originalNetwork = null;
            for (int i = 0; i < networks.length(); i++) if (networks.getJSONObject(i).getString("pcId").equals(pcIds.get(0)))
                originalNetwork = networks.getJSONObject(i).getJSONObject("network");
            require(originalNetwork != null, "Missing original network record");
            JSONArray reassociated = new JSONArray(), removed = new JSONArray(), repaired = new JSONArray();
            for (int index : new int[]{0, 10, 11}) {
                String address = addresses.getString(index); reassociated.put(address);
                removed.put(new JSONObject().put("address", address).put("pcId", JSONObject.NULL).put("pcName", JSONObject.NULL)
                        .put("ipAddresses", new JSONArray()).put("observedAtMs", System.currentTimeMillis()));
                repaired.put(metadata.getJSONObject(index));
            }
            request(origin, token, "/mobile/fleets", new JSONObject().put("op", "merge").put("id", fleet)
                    .put("addresses", reassociated).put("pcMetadata", removed));
            long missingDeadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks").length() != 3
                    && android.os.SystemClock.elapsedRealtime() < missingDeadline) Thread.sleep(100);
            require(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks").length() == 3,
                    "Receiver-loss fixture did not remove its scoped PC record");
            request(origin, token, "/mobile/fleets", new JSONObject().put("op", "merge").put("id", fleet)
                    .put("addresses", reassociated).put("pcMetadata", repaired));
            instrumentation.runOnMainSync(() -> { running.pause(); running.resume(); });
            // Honor the actual existing one-minute local retry deadline rather than reset it.
            JSONObject deliveredAgain = null;
            long retryDeadline = android.os.SystemClock.elapsedRealtime() + 75_000;
            while (android.os.SystemClock.elapsedRealtime() < retryDeadline) {
                JSONArray next = request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks");
                for (int i = 0; i < next.length(); i++) if (next.getJSONObject(i).getString("pcId").equals(pcIds.get(0)))
                    deliveredAgain = next.getJSONObject(i).getJSONObject("network");
                if (deliveredAgain != null) break;
                Thread.sleep(250);
            }
            require(deliveredAgain != null && MonitoringController.receiverNetwork(deliveredAgain).json().equals(
                    MonitoringController.receiverNetwork(originalNetwork).json()) && ipReads.get() == 4,
                    "A missing receiver record did not recover from unchanged native cache without another source read");
            JSONArray retained = new DiscoveryStore(activity, namespace).monitoringTargets();
            require(retained.length() == restored.length() && retained.getString(0).equals("invalid-target"), "Malformed saved records were silently removed");
            for (int index = 0; index < targets.size(); index++) {
                JSONObject original = targets.get(index), after = retained.getJSONObject(index + 2);
                for (String field : new String[]{"approved", "paused", "status", "authorizationId", "credentialId", "keyBase64", "bindings", "sudoPassword", "lastGpuAttemptAtMs"}) {
                    if (index == 9 && field.equals("lastGpuAttemptAtMs")) continue; // This independently valid node worker legitimately ran.
                    require(java.util.Objects.equals(String.valueOf(original.opt(field)), String.valueOf(after.opt(field))), "IP work changed saved authority or node state: " + field);
                }
            }
            reopened.restrictMonitoring(targets.get(1), addresses.getString(11), true, false, "authorization-required", 0);
            require(!new DiscoveryStore(activity, namespace).monitoringTargets().getJSONObject(3).optBoolean("rewardsAuthorized"),
                    "A restriction reauthorized a wallet because duplicate malformed bindings inflated its count");
            verifyExistingPcRepair(activity, reopened, origin, token, fleet, profile, credential, targets.get(0));
            long providerWait = System.currentTimeMillis() + 3_600_000;
            reopened.restrictMonitoring(targets.get(0), addresses.getString(10), true, false, "authorization-required", providerWait);
            DiscoveryStore walletDenied = new DiscoveryStore(activity, namespace);
            JSONObject walletRecord = walletDenied.monitoringTargets().getJSONObject(2);
            require(!walletRecord.optBoolean("rewardsAuthorized") && walletRecord.getJSONArray("rewardsSuspended").toString().contains(addresses.getString(10))
                    && walletDenied.rewardsRetryAtMs() >= providerWait, "Wallet denial or provider wait was lost behind malformed node records");
            require(MonitoringController.permittedNetworkObservation(walletDenied, targets.get(0)), "A wallet denial replaced a node-binding pause and blocked independent PC IP");
            require(reopened.suspendCredentialProfile(targets.get(0)), "Genuine credential rejection could not be persisted past malformed records");
            DiscoveryStore denied = new DiscoveryStore(activity, namespace);
            for (int index : new int[]{0, 1, 2, 9})
                require(!MonitoringController.permittedNetworkObservation(denied, targets.get(index)), "Shared rejection left malformed-node IP authority active");
            running.pauseFleet(fleet);
            JSONArray paused = new DiscoveryStore(activity, namespace).monitoringTargets();
            for (int i = 0; i < paused.length(); i++) {
                JSONObject target = paused.optJSONObject(i);
                if (target != null && fleet.equals(target.optString("fleetId")))
                    require(!target.optBoolean("approved") && target.optBoolean("paused") && "paused".equals(target.optString("status")),
                            "Manual pause failed to revoke a malformed-node target");
            }
            instrumentation.runOnMainSync(running::resume); Thread.sleep(1200);
            require(ipReads.get() == 4, "Manual pause or shared credential rejection allowed more IP reads");
        } finally {
            if (controller != null) { final MonitoringController ending = controller; instrumentation.runOnMainSync(ending::destroy); }
            if (fleet != null) request(origin, token, "/mobile/fleets", new JSONObject().put("op", "delete").put("id", fleet));
            android.content.SharedPreferences preferences = activity.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor cleanup = preferences.edit();
            for (String preference : preferences.getAll().keySet()) for (String pc : pcIds) if (preference.startsWith(pc)) cleanup.remove(preference);
            require(cleanup.commit(), "Fixture cache cleanup failed");
            store.forgetCredentials(); for (com.jcraft.jsch.KeyPair key : keys) key.dispose();
            File[] files = directory.listFiles(); if (files != null) for (File file : files) require(file.delete(), "Fixture file cleanup failed");
            require(directory.delete(), "Fixture directory cleanup failed");
        }
    }
    private static void verifyPcOnlyRegistration(MainActivity activity, DiscoveryStore store, String origin, String token,
            String fleet, String profile, String credentialId, JSONObject target) throws Exception {
        MonitoringController registration = new MonitoringController(activity, store, origin, token);
        SshDiscovery.Identity identity = new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm"));
        DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "");
        try (SshDiscovery.Credential credential = new SshDiscovery.Credential("fixture", "disposable-fixture-password".getBytes(StandardCharsets.UTF_8), null, null)) {
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(candidate, identity, credential, null);
            PcMetadata metadata = PcMetadata.observed(identity, candidate.address, "fixture-pc", candidate.address, System.currentTimeMillis());
            SshDiscovery.Result discovered = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, java.util.Collections.emptyList(), 1,
                    metadata, null, java.util.Collections.emptyList());
            java.util.Map<String, String> credentials = java.util.Collections.singletonMap(candidate.endpointKey(), credentialId);
            java.util.Map<String, SshDiscovery.Result> results = java.util.Collections.singletonMap(candidate.endpointKey(), discovered);
            require(registration.register(fleet, profile, java.util.Collections.singletonList(request), results, credentials,
                    java.util.Collections.emptyMap(), false).isEmpty() && store.monitoringTargets().length() == 0,
                    "PC-only discovery granted monitoring without explicit approval");
            SshDiscovery.Result denied = new SshDiscovery.Result(SshDiscovery.Status.AUTH_FAILED, java.util.Collections.emptyList(), 1,
                    metadata, null, java.util.Collections.emptyList());
            require(registration.register(fleet, profile, java.util.Collections.singletonList(request),
                    java.util.Collections.singletonMap(candidate.endpointKey(), denied), credentials, java.util.Collections.emptyMap(), true).isEmpty(),
                    "Failed SSH authentication registered a PC-only target");
            List<JSONObject> enabled = registration.register(fleet, profile, java.util.Collections.singletonList(request), results,
                    credentials, java.util.Collections.emptyMap(), true);
            require(enabled.size() == 1 && enabled.get(0).getJSONArray("bindings").length() == 0
                    && enabled.get(0).getBoolean("approved") && enabled.get(0).getBoolean("paused")
                    && !enabled.get(0).getBoolean("rewardsAuthorized") && !enabled.get(0).getBoolean("setupPending")
                    && MonitoringController.permittedNetworkObservation(store, enabled.get(0)),
                    "Explicit verified PC approval still required a node binding for IP");
            require(store.credentialProfiles().length() == 1, "PC registration replaced remembered credentials");
            store.removeMonitoringFleet(fleet);
        } finally { registration.destroy(); }
    }
    private static void verifyExistingPcRepair(MainActivity activity, DiscoveryStore store, String origin, String token,
            String fleet, String profile, String credentialId, JSONObject target) throws Exception {
        MonitoringController registration = new MonitoringController(activity, store, origin, token);
        SshDiscovery.Identity identity = new SshDiscovery.Identity(target.getString("keyBase64"), target.getString("algorithm"));
        DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate(target.getString("address"), target.getInt("port"), "");
        try (SshDiscovery.Credential credential = new SshDiscovery.Credential("fixture", "disposable-fixture-password".getBytes(StandardCharsets.UTF_8), null, null)) {
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(candidate, identity, credential, null);
            PcMetadata metadata = PcMetadata.observed(identity, candidate.address, "fixture-pc", candidate.address, System.currentTimeMillis());
            SshDiscovery.Result discovered = new SshDiscovery.Result(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, java.util.Collections.emptyList(), 3,
                    metadata, null, java.util.Collections.emptyList());
            List<JSONObject> repaired = registration.register(fleet, profile, java.util.Collections.singletonList(request),
                    java.util.Collections.singletonMap(candidate.endpointKey(), discovered), java.util.Collections.singletonMap(candidate.endpointKey(), credentialId),
                    java.util.Collections.emptyMap(), true);
            require(repaired.size() == 1, "PC-only repair was blocked by unrelated or own malformed node state");
            JSONArray saved = store.monitoringTargets(); JSONObject actual = null;
            for (int i = 0; i < saved.length(); i++) if (saved.optJSONObject(i) != null && target.getString("id").equals(saved.getJSONObject(i).optString("id"))) actual = saved.getJSONObject(i);
            require(actual != null && MonitoringController.permittedNetworkObservation(store, actual), "PC-only repair did not authorize its verified PC");
            for (String field : new String[]{"bindings", "rewardsSuspended", "rewardsVerified", "gpuCursors", "rewardsRetryAtMs", "authorizationId", "rewardsAuthorized"})
                require(java.util.Objects.equals(String.valueOf(target.opt(field)), String.valueOf(actual.opt(field))), "PC-only repair rewrote node history or source policy: " + field);
        } finally { registration.destroy(); }
    }
    private static void verifyReceiverParsing() throws Exception {
        // Receiver property order differs from the native cache's canonical serialization.
        JSONObject reversed = new JSONObject().put("source", "pc-egress-ipify").put("privateObservedAtMs", 900)
                .put("privateIp", "fd12::20").put("publicObservedAtMs", 1000).put("publicIp", "2606:4700::1111")
                .put("status", "ok").put("observedAtMs", 1000).put("schema", 1);
        require(MonitoringController.receiverNetwork(reversed).publicIp.contains(":"), "Reordered receiver JSON lost a valid IPv6 source");
        for (String field : new String[]{"source", "schema", "publicObservedAtMs"}) {
            JSONObject invalid = new JSONObject(reversed.toString()).put(field, new JSONArray().put(reversed.get(field)));
            boolean rejected = false;
            try { MonitoringController.receiverNetwork(invalid); } catch (Exception expected) { rejected = true; }
            require(rejected, "Receiver JSON accepted a coercible array field");
        }
    }
    private static JSONObject request(String origin, String token, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
        connection.setRequestProperty("X-GridLens-Token", token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            require(connection.getResponseCode() == 200, "Saved target fixture API request failed");
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(NodeRuntime.readBounded(input, 256 * 1024), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private SavedTargetIpVerification() { }
}
