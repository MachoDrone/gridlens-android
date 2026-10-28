package com.gridlens.android;

import android.app.Instrumentation;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real controller, encrypted vault, embedded observation API/storage and rendered WebView.
 * Only source transport is injected; separate JVM/HTTPS fixtures execute actual commands. */
final class PcNetworkVerification {
    static void verify(Instrumentation instrumentation, MainActivity activity, String origin, String token) throws Exception {
        require(BuildConfig.DEBUG, "Debug fixture required");
        String namespace = "pc-network-controller-verification";
        File directory = new File(activity.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "PC network fixture requires unused storage");
        DiscoveryStore store = new DiscoveryStore(activity, namespace);
        List<com.jcraft.jsch.KeyPair> keys = new ArrayList<>();
        List<JSONObject> targets = new ArrayList<>();
        JSONArray metadata = new JSONArray(), addresses = new JSONArray();
        String fleet = null;
        MonitoringController controller = null;
        CountDownLatch nodeStarted = new CountDownLatch(3), nodeRelease = new CountDownLatch(1);
        CountDownLatch cancelledStarted = new CountDownLatch(1), cancelledRelease = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger(), activeNodes = new AtomicInteger(), maximumNodes = new AtomicInteger();
        AtomicInteger rejectedGpu = new AtomicInteger(), intercepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WebViewClient> priorClient = new AtomicReference<>();
        final long at = System.currentTimeMillis() - 1000;
        try {
            String profile = store.saveProfile(null, "Isolated PC IP", new JSONArray().put("192.0.2.0/24"), new JSONArray().put(22));
            String credential = store.saveCredential(new JSONObject().put("name", "Isolated PC IP").put("username", "fixture")
                    .put("password", "disposable-fixture-password").put("key", "").put("passphrase", ""), null);
            for (int index = 0; index < 5; index++) {
                com.jcraft.jsch.KeyPair key = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
                keys.add(key);
                SshDiscovery.Identity identity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(key.getPublicKeyBlob()), "ssh-rsa");
                String host = "1".repeat(31) + (index + 1), address = "192.0.2." + (20 + index), pc = MonitoringController.pcId(identity);
                store.trust(profile, "[" + address + "]:22", identity.keyBase64);
                addresses.put(host);
                metadata.put(new JSONObject().put("address", host).put("pcId", pc).put("pcName", "fixture-pc-" + index)
                        .put("ipAddresses", new JSONArray().put(address)).put("observedAtMs", at));
                JSONObject binding = new JSONObject().put("runtime", "docker").put("wrapperId", JSONObject.NULL)
                        .put("nodeContainerId", "a".repeat(63) + index).put("address", host);
                targets.add(new JSONObject().put("id", UUID.randomUUID().toString()).put("profileId", profile)
                        .put("address", address).put("port", 22).put("keyBase64", identity.keyBase64).put("algorithm", identity.algorithm)
                        .put("pcId", pc).put("credentialId", credential).put("approved", index < 4).put("paused", index == 0 || index == 4)
                        .put("status", index == 0 || index == 4 ? "binding-changed" : "active").put("rewardsAuthorized", false)
                        .put("authorizationId", UUID.randomUUID().toString()).put("setupPending", false)
                        .put("bindings", new JSONArray().put(binding)));
            }
            // Two hosts on the first stopped-node PC must share the one observation.
            addresses.put("1".repeat(31) + "6");
            metadata.put(new JSONObject(metadata.getJSONObject(0).toString()).put("address", "1".repeat(31) + "6"));
            JSONObject created = request(origin, token, "/mobile/fleets", new JSONObject().put("op", "create")
                    .put("name", "Isolated controller IP").put("addresses", addresses).put("pcMetadata", metadata), 200);
            fleet = created.getString("fleetId");
            for (JSONObject target : targets) { target.put("fleetId", fleet); store.saveMonitoringTarget(target); }
            JSONObject firstBinding = new JSONObject(targets.get(0).getJSONArray("bindings").getJSONObject(0).toString());
            String firstAddress = firstBinding.getString("address"); firstBinding.remove("address"); firstBinding.put("pcId", targets.get(0).getString("pcId"));
            JSONObject reward = new JSONObject().put("schema", 1).put("address", firstAddress).put("status", "ok")
                    .put("amount", "12.345678").put("observedAtMs", at);
            JSONObject gpu = new JSONObject().put("schema", 2).put("address", firstAddress).put("status", "ok").put("observedAtMs", at)
                    .put("gpuUuids", new JSONArray().put("GPU-00000000-1111-2222-3333-444444444444"))
                    .put("intervalSeconds", 15).put("fromAtMs", at).put("toAtMs", at).put("truncated", false).put("clockTrusted", true)
                    .put("samples", new JSONArray().put(new JSONArray().put(at).put(0).put(15).put(0).put(0).put(0).put(0).put(0)));
            JSONObject seed = new JSONObject().put("address", firstAddress).put("binding", firstBinding).put("rewards", reward).put("gpu", gpu)
                    .put("monitoring", new JSONObject().put("status", "binding-changed").put("checkedAtMs", at));
            request(origin, token, "/mobile/observations", envelope(fleet, seed), 200);
            String preserved = request(origin, token, "/mobile/observations?fleet=" + fleet + "&address=" + firstAddress, null, 200).getJSONObject("host").toString();
            final String savedFleet = fleet;
            controller = new MonitoringController(activity, store, origin, token, (target, login, permitted) -> {
                reads.incrementAndGet();
                if (target.getString("pcId").equals(targets.get(4).getString("pcId"))) {
                    cancelledStarted.countDown(); cancelledRelease.await(15, TimeUnit.SECONDS);
                    if (!permitted.getAsBoolean()) return new SshDiscovery.NetworkResult("cancelled", null);
                }
                return new SshDiscovery.NetworkResult("active", PublicIpReader.read(target.getString("address"), (command, maximum, timeout) -> {
                    require(permitted.getAsBoolean(), "Revoked IP command dispatched");
                    if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "192.168.10.42\n");
                    require(command.equals(PublicIpReader.command(4)), "IP command uses a container or unexpected transport");
                    return new PublicIpReader.Response(0, "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n8.8.8.8\n");
                }, System::currentTimeMillis));
            }, (target, enable, rewards, collectGpu) -> {
                int count = activeNodes.incrementAndGet(); maximumNodes.accumulateAndGet(count, Math::max); nodeStarted.countDown();
                try {
                    JSONObject broken = new JSONObject().put("address", firstAddress).put("binding", firstBinding)
                            .put("gpu", new JSONObject().put("malformed", true));
                    request(origin, token, "/mobile/observations", envelope(savedFleet, broken), 400); rejectedGpu.incrementAndGet();
                    nodeRelease.await(40, TimeUnit.SECONDS);
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { activeNodes.decrementAndGet(); }
            });
            final MonitoringController running = controller;
            instrumentation.runOnMainSync(() -> { running.selectedScenario("fleet:" + savedFleet); running.resume(); });
            require(nodeStarted.await(10, TimeUnit.SECONDS), "Three independent node workers did not start");
            JSONObject observations = awaitNetworks(origin, token, fleet, 4);
            require(maximumNodes.get() == 3 && activeNodes.get() == 3, "Node work consumed the reserved IP slot or stopped early");
            require(reads.get() == 4, "IP lane did not independently read each approved PC exactly once");
            require(rejectedGpu.get() == 3 && failure.get() == null, "Malformed GPU fixture failed independently");
            require(observations.getJSONArray("pcNetworks").length() == 4, "IP readings lost while node workers were blocked");
            require(preserved.equals(request(origin, token, "/mobile/observations?fleet=" + fleet + "&address=" + firstAddress, null, 200)
                    .getJSONObject("host").toString()), "IP delivery changed rewards, GPU, binding or node status");
            // Intercept only provider routes before they reach Node. Real fleet,
            // observation, static-asset and connection APIs remain in use.
            instrumentation.runOnMainSync(() -> {
                priorClient.set(activity.webViewForTesting().getWebViewClient());
                activity.webViewForTesting().setWebViewClient(new WebViewClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, WebResourceRequest request) {
                        String path = request.getUrl().getPath();
                        if (path != null && path.startsWith("/preview/") && path.endsWith(".json")
                                && !path.equals("/preview/hosts.json") && !path.equals("/preview/connection.json")) {
                            intercepted.incrementAndGet();
                            return new WebResourceResponse("application/json", "UTF-8", new ByteArrayInputStream("{\"hosts\":[]}".getBytes(StandardCharsets.UTF_8)));
                        }
                        require("127.0.0.1".equals(request.getUrl().getHost()), "Fixture attempted an external browser request");
                        return priorClient.get().shouldInterceptRequest(view, request);
                    }
                });
                activity.webViewForTesting().loadUrl(origin + "/?scenario=fleet:" + savedFleet);
            });
            awaitJavascript(instrumentation, activity, "document.querySelectorAll('#fleet-rows > tr[data-host]').length === 6");
            awaitJavascript(instrumentation, activity, "document.getElementById('ip-display-toggle').dataset.mode==='dot'");
            javascript(instrumentation, activity, "document.getElementById('ip-display-toggle').click()");
            awaitJavascript(instrumentation, activity, "Array.from(document.querySelectorAll('#fleet-rows > tr[data-host] td[data-col=ip]')).filter(e=>e.textContent==='8.8.8.8 / 192.168.10.42').length===5");
            require(intercepted.get() > 0, "Public-provider isolation was not exercised");
            for (String expected : new String[]{"8.8.8.8", ".8", "192.168.10.42", ".42", "•"}) {
                javascript(instrumentation, activity, "document.getElementById('ip-display-toggle').click()");
                awaitJavascript(instrumentation, activity, "document.querySelector('#fleet-rows > tr[data-host=\"" + firstAddress + "\"] td[data-col=ip]').textContent===" + JSONObject.quote(expected));
            }
            require(reads.get() == 4, "Display taps initiated collection");
            // A read that finishes after background/revocation must not deliver.
            JSONObject cancelled = new JSONObject(targets.get(4).toString()).put("approved", true);
            store.saveMonitoringTarget(cancelled);
            instrumentation.runOnMainSync(running::resume);
            require(cancelledStarted.await(10, TimeUnit.SECONDS), "Cancellation fixture IP read did not start");
            instrumentation.runOnMainSync(running::pause);
            store.saveMonitoringTarget(new JSONObject(cancelled.toString()).put("approved", false).put("paused", true).put("status", "paused"));
            cancelledRelease.countDown(); Thread.sleep(300);
            require(request(origin, token, "/mobile/observations?fleet=" + fleet, null, 200).getJSONArray("pcNetworks").length() == 4,
                    "Cancelled or revoked observation was delivered");
            require(!MonitoringController.permittedNetworkObservation(store, cancelled), "Revocation was ignored by PC lane");
        } finally {
            if (controller != null) { final MonitoringController ending = controller; instrumentation.runOnMainSync(ending::destroy); }
            cancelledRelease.countDown(); nodeRelease.countDown();
            if (priorClient.get() != null) instrumentation.runOnMainSync(() -> {
                activity.webViewForTesting().loadUrl(origin + "/?scenario=fresh");
                activity.webViewForTesting().setWebViewClient(priorClient.get());
            });
            if (fleet != null) request(origin, token, "/mobile/fleets", new JSONObject().put("op", "delete").put("id", fleet), 200);
            android.content.SharedPreferences preferences = activity.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor cleanup = preferences.edit();
            for (String preference : preferences.getAll().keySet()) for (JSONObject target : targets)
                if (preference.startsWith(target.getString("pcId"))) cleanup.remove(preference);
            require(cleanup.commit(), "Fixture network cache cleanup failed");
            store.forgetCredentials(); for (com.jcraft.jsch.KeyPair key : keys) key.dispose();
            File[] files = directory.listFiles(); if (files != null) for (File file : files) require(file.delete(), "Fixture file cleanup failed");
            require(directory.delete(), "Fixture directory cleanup failed");
        }
    }
    private static JSONObject envelope(String fleet, JSONObject update) throws Exception {
        return new JSONObject().put("schema", 1).put("fleetId", fleet).put("updates", new JSONArray().put(update));
    }
    private static JSONObject awaitNetworks(String origin, String token, String fleet, int count) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            JSONObject response = request(origin, token, "/mobile/observations?fleet=" + fleet, null, 200);
            if (response.getJSONArray("pcNetworks").length() == count) return response;
            Thread.sleep(100);
        }
        throw new AssertionError("Controller did not deliver independent PC networks");
    }
    private static void awaitJavascript(Instrumentation instrumentation, MainActivity activity, String expression) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(javascript(instrumentation, activity, expression))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Rendered PC network assertion failed: " + expression);
    }
    private static String javascript(Instrumentation instrumentation, MainActivity activity, String expression) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> activity.webViewForTesting().evaluateJavascript(expression, result -> { value.set(result); done.countDown(); }));
        require(done.await(5, TimeUnit.SECONDS), "PC network WebView JavaScript timed out"); return value.get();
    }
    private static JSONObject request(String origin, String token, String path, JSONObject body, int expected) throws Exception {
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
            int status = connection.getResponseCode(); require(status == expected, "Fixture API response " + status + " expected " + expected);
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return new JSONObject(new String(NodeRuntime.readBounded(input, 256 * 1024), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private PcNetworkVerification() { }
}
