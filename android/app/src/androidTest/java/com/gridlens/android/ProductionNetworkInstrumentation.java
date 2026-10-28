package com.gridlens.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Real Android capture/SSH/cache/vault/controller/API; only endpoint is emulator host loopback. */
public final class ProductionNetworkInstrumentation extends Instrumentation {
    private Bundle arguments;
    private Instrumentation driver = this;
    static void verify(Instrumentation provided, Bundle arguments) {
        ProductionNetworkInstrumentation fixture = new ProductionNetworkInstrumentation();
        fixture.driver = provided; fixture.arguments = arguments; fixture.onStart();
    }
    @Override public void onCreate(Bundle supplied) { super.onCreate(supplied); arguments = supplied; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); MonitoringController controller = null; DiscoveryStore store = null;
        String origin = null, token = null, fleet = null, pcId = null;
        final String namespace = "production-network-verification";
        int outcome = Activity.RESULT_CANCELED;
        try {
            require(BuildConfig.DEBUG, "Debug fixture required");
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"), "Disposable emulator required");
            require(arguments != null, "Generated SSH fixture arguments required");
            int port = Integer.parseInt(arguments.getString("fixturePort", "0"));
            require(port > 1024 && port <= 65535, "Bounded fixture port required");
            SshDiscovery.Identity identity = new SshDiscovery.Identity(arguments.getString("fixtureKey"), arguments.getString("fixtureAlgorithm", "ssh-rsa"));
            require(!new File(driver.getTargetContext().getNoBackupFilesDir(), namespace).exists(), "Unused fixture namespace required");
            MainActivity activity = (MainActivity) driver.startActivitySync(new Intent(driver.getTargetContext(), MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh"));
            JSONObject ready = null; long until = android.os.SystemClock.elapsedRealtime() + 60000;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                ready = NodeRuntime.ready(driver.getTargetContext()); if (ready != null) break; Thread.sleep(100);
            }
            require(ready != null, "Embedded runtime did not start");
            origin = "http://127.0.0.1:" + ready.getInt("port"); token = ready.getString("token");
            require(request(origin, token, "/mobile/fleets", null).getJSONArray("fleets").length() == 0, "Disposable emulator must contain no saved fleets");
            store = new DiscoveryStore(driver.getTargetContext(), namespace);
            String profile = store.saveProfile(null, "Isolated SSH production", new JSONArray().put("10.0.2.0/24"), new JSONArray().put(port));
            store.trust(profile, "[10.0.2.2]:" + port, identity.keyBase64);
            String credential = store.saveCredential(new JSONObject().put("name", "Generated fixture").put("username", "fixture")
                    .put("password", "isolated-ssh-password").put("key", "").put("passphrase", ""), identity.keyBase64);
            pcId = MonitoringController.pcId(identity);
            JSONArray addresses = new JSONArray(), metadata = new JSONArray(), bindings = new JSONArray(); long at = System.currentTimeMillis();
            for (int i = 1; i <= 3; i++) {
                String address = "1".repeat(31) + i; addresses.put(address);
                metadata.put(new JSONObject().put("address", address).put("pcId", pcId).put("pcName", "three-host-fixture")
                        .put("ipAddresses", new JSONArray().put("10.0.2.2")).put("observedAtMs", at));
                bindings.put(new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                        .put("nodeContainerId", "a".repeat(63) + i).put("address", address));
            }
            fleet = request(origin, token, "/mobile/fleets", new JSONObject().put("op", "create").put("name", "Production SSH fixture")
                    .put("addresses", addresses).put("pcMetadata", metadata)).getString("fleetId");
            JSONObject target = new JSONObject().put("id", UUID.randomUUID().toString()).put("fleetId", fleet).put("profileId", profile)
                    .put("address", "10.0.2.2").put("port", port).put("pcId", pcId).put("keyBase64", identity.keyBase64).put("algorithm", identity.algorithm)
                    .put("credentialId", credential).put("approved", true).put("paused", false).put("status", "unsupported")
                    .put("rewardsAuthorized", false).put("authorizationId", UUID.randomUUID().toString()).put("setupPending", false).put("bindings", bindings);
            store.saveMonitoringTarget(target);
            controller = new MonitoringController(activity, store, origin, token);
            final MonitoringController running = controller; final String selected = fleet;
            driver.runOnMainSync(() -> { running.selectedScenario("fleet:" + selected); running.resume(); });
            JSONObject observations = null; until = android.os.SystemClock.elapsedRealtime() + 45000;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                observations = request(origin, token, "/mobile/observations?fleet=" + fleet, null);
                if (observations.getJSONArray("pcNetworks").length() == 1 && observations.getJSONArray("hosts").length() == 3) break;
                Thread.sleep(100);
            }
            require(observations != null && observations.getJSONArray("pcNetworks").length() == 1,
                    "Real production IP path produced no PC observation; native status=" + store.monitoringTargets().getJSONObject(0).optString("status")
                    + ", connections=" + DiscoveryNetwork.startedConnectionsForTesting());
            JSONObject network = observations.getJSONArray("pcNetworks").getJSONObject(0).getJSONObject("network");
            require("8.8.8.8".equals(network.optString("publicIp")), "Production curl result missing: " + network.optString("publicFailure"));
            require("192.168.10.101".equals(network.optString("privateIp")), "Production interface result missing");
            require("pc-egress-ipify".equals(network.getString("source")), "Wrong production IP source");
            require(observations.getJSONArray("hosts").length() == 3, "Three production node results did not arrive");
            for (int i = 0; i < 3; i++) require("unsupported".equals(observations.getJSONArray("hosts").getJSONObject(i)
                    .getJSONObject("monitoring").getString("status")), "GPU namespace fixture did not exercise unsupported");
            require(DiscoveryNetwork.startedSearchesForTesting() == 0, "Production polling started a scan");
            require(DiscoveryNetwork.startedConnectionsForTesting() >= 2, "Real Android SSH sockets were bypassed");
            result.putString("stream", "PASS: real Android network capture, SSH authentication, three unsupported node bindings, PC IP collection, native cache and embedded observation API. No operator PC, shell execution or public provider calls.\n");
            outcome = Activity.RESULT_OK;
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally {
            if (controller != null) { MonitoringController ending = controller; driver.runOnMainSync(ending::destroy); }
            try { if (fleet != null) request(origin, token, "/mobile/fleets", new JSONObject().put("op", "delete").put("id", fleet)); } catch (Exception ignored) { }
            if (store != null) try { store.forgetCredentials(); } catch (Exception ignored) { }
            File directory = new File(driver.getTargetContext().getNoBackupFilesDir(), namespace);
            File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); directory.delete();
            if (pcId != null) {
                android.content.SharedPreferences cache = driver.getTargetContext().getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
                android.content.SharedPreferences.Editor cleanup = cache.edit();
                for (String key : cache.getAll().keySet()) if (key.startsWith(pcId)) cleanup.remove(key);
                cleanup.commit();
            }
        }
        driver.finish(outcome, result);
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
            require(connection.getResponseCode() == 200, "Fixture API rejected request");
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(NodeRuntime.readBounded(input, 256 * 1024), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
