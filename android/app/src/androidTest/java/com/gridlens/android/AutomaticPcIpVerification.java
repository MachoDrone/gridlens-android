package com.gridlens.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Normal native discovery/review/save against generated loopback SSH only. */
final class AutomaticPcIpVerification {
    static void verify(Instrumentation driver, Bundle arguments) {
        Bundle result = new Bundle(); int outcome = Activity.RESULT_CANCELED;
        MainActivity activity = null; DiscoveryController discovery = null; DiscoveryStore store = null;
        android.webkit.WebViewClient originalClient = null;
        String origin = null, token = null, profile = null;
        List<String> fleets = new ArrayList<>(), pcs = new ArrayList<>();
        try {
            require(BuildConfig.DEBUG, "Debug fixture required");
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu"), "Disposable emulator required");
            int port = Integer.parseInt(arguments.getString("fixturePort", "0"));
            require(port > 1024 && port <= 65535, "Bounded generated SSH fixture port required");
            DiscoveryNetwork.fixtureEndpointForTesting(port);
            activity = (MainActivity) driver.startActivitySync(new Intent(driver.getTargetContext(), MainActivity.class)
                    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh"));
            JSONObject ready = awaitRuntime(driver);
            origin = "http://127.0.0.1:" + ready.getInt("port"); token = ready.getString("token");
            require(request(origin, token, "/mobile/fleets", null).getJSONArray("fleets").length() == 0,
                    "Automatic IP verification requires an empty disposable app");
            discovery = awaitDiscovery(driver, activity);
            awaitDiscoveryNetwork(driver);
            verifyFixtureRestriction(driver, port);
            store = (DiscoveryStore) field(discovery, "store");
            require(store != null && store.profiles().length() == 0 && store.knownEndpoints().length() == 0
                    && store.credentialProfiles().length() == 0 && store.monitoringTargets().length() == 0,
                    "Automatic IP verification requires an empty native vault");
            // Keep production native navigation, local fleet/observation delivery and
            // rendering. Synthetic host addresses must never reach public readers.
            java.util.concurrent.atomic.AtomicReference<android.webkit.WebViewClient> client = new java.util.concurrent.atomic.AtomicReference<>();
            MainActivity startingScreen = activity;
            driver.runOnMainSync(() -> {
                android.webkit.WebViewClient delegate = startingScreen.webViewForTesting().getWebViewClient(); client.set(delegate);
                startingScreen.webViewForTesting().setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                        return delegate.shouldOverrideUrlLoading(view, request);
                    }
                    @Override public android.webkit.WebResourceResponse shouldInterceptRequest(android.webkit.WebView view, android.webkit.WebResourceRequest request) {
                        String path = request.getUrl().getPath();
                        if (path != null && path.startsWith("/preview/") && path.endsWith(".json")
                                && !path.equals("/preview/hosts.json") && !path.equals("/preview/connection.json"))
                            return new android.webkit.WebResourceResponse("application/json", "UTF-8",
                                    new java.io.ByteArrayInputStream("{\"hosts\":[]}".getBytes(StandardCharsets.UTF_8)));
                        return delegate.shouldInterceptRequest(view, request);
                    }
                    @Override public void onPageStarted(android.webkit.WebView view, String url, android.graphics.Bitmap icon) { delegate.onPageStarted(view, url, icon); }
                    @Override public void onPageFinished(android.webkit.WebView view, String url) { delegate.onPageFinished(view, url); }
                    @Override public void doUpdateVisitedHistory(android.webkit.WebView view, String url, boolean reload) { delegate.doUpdateVisitedHistory(view, url, reload); }
                });
            });
            originalClient = client.get();
            // Supply only the generated fixture's one bounded network destination.
            // Trust, credentials, review, inspection and saving use the normal native UI.
            String profileName = "Automatic IP local fixture";
            profile = store.saveProfile(null, profileName, new JSONArray().put("10.0.2.2/32"), new JSONArray().put(port));
            long initialSearches = DiscoveryNetwork.startedSearchesForTesting();
            boolean ephemeralOnly = "true".equals(arguments.getString("ephemeralOnly"));
            discover(driver, discovery, profileName, port, !ephemeralOnly, false);
            awaitText(driver, "Fleet saved");
            require(hasText(driver, "3 verified public host addresses"), "Normal inventory did not save all three arbitrary-name nodes");
            require(clickText(driver, "OK"), "Could not close normal fleet save result");
            JSONArray saved = request(origin, token, "/mobile/fleets", null).getJSONArray("fleets");
            require(saved.length() == 1, "Normal discovery did not create exactly one fleet");
            String fleet = saved.getJSONObject(0).getString("id"); fleets.add(fleet);
            if (ephemeralOnly) {
                require(store.credentialProfiles().length() == 0 && store.monitoringTargets().length() == 0,
                        "Automatic IP silently remembered an explicitly ephemeral credential or granted persistent access");
                Thread.sleep(1500);
                require(request(origin, token, "/mobile/observations?fleet=" + fleet, null).getJSONArray("pcNetworks").length() == 0,
                        "Ephemeral credential started persistent automatic IP collection");
                require(DiscoveryNetwork.startedSearchesForTesting() == initialSearches + 1,
                        "Ephemeral discovery caused an unrequested additional search");
                stage(driver, "automatic_ip_ephemeral_review");
                result.putString("stream", "PASS: fresh native search→SSH trust→Remember unchecked credentials→normal Inspect + automatic IP review→"
                        + "three arbitrary-name node discoveries→one saved public fleet; no persisted credential, automatic target or PC-IP reading. "
                        + "Generated loopback SSH fixture only; no operator PC or existing private keys.\n");
            } else {
                JSONObject target = awaitTarget(store, fleet);
                String pc = target.getString("pcId"); pcs.add(pc);
                require(target.optBoolean("pcIpApproved", target.optBoolean("approved")) && !target.optBoolean("pcIpPaused"),
                        "Normal review did not authorize PC IP checks");
                require(target.optBoolean("paused") && !target.optBoolean("rewardsAuthorized")
                        && !target.optBoolean("setupPending") && !target.optBoolean("sudoApproved")
                        && target.getJSONArray("bindings").length() == 0,
                        "Normal automatic IP review granted node, wallet, sudo or GPU setup authority");
                require(store.credentialProfiles().length() == 1, "Remembered native credential was not retained once");
                JSONObject observations = awaitNetwork(origin, token, fleet);
                JSONObject network = observations.getJSONArray("pcNetworks").getJSONObject(0).getJSONObject("network");
                require("8.8.8.8".equals(network.optString("publicIp")) && "192.168.10.101".equals(network.optString("privateIp"))
                        && "pc-egress-ipify".equals(network.optString("source")), "Automatic PC egress/interface result did not arrive");
                require(observations.getJSONArray("hosts").length() == 0, "Automatic PC-IP enrollment delivered node/reward/GPU observations");
                require(DiscoveryNetwork.startedSearchesForTesting() == initialSearches + 1,
                        "Automatic IP monitoring unexpectedly repeated discovery");
                awaitJavascript(driver, activity, "document.querySelectorAll('#fleet-rows > tr[data-host]').length===3");
                for (int i = 0; i < 6 && !"\"full\"".equals(javascript(driver, activity,
                        "document.getElementById('ip-display-toggle').dataset.mode")); i++)
                    javascript(driver, activity, "document.getElementById('ip-display-toggle').click()");
                // Normal save opens the page before the first PC read may finish. Allow
                // the existing 30-second cache poll plus its request allowance; no manual refresh.
                awaitJavascript(driver, activity, "Array.from(document.querySelectorAll('#fleet-rows > tr[data-host] td[data-col=ip]'))"
                        + ".filter(e=>e.textContent==='8.8.8.8 / 192.168.10.101').length===3", 40000);
                require(!hasText(driver, "Allow IP checks") && !hasText(driver, "IP check: three-host-fixture"),
                        "Ordinary discovery unexpectedly required the manual IP repair flow");
                stage(driver, "automatic_ip_normal_review_three_rendered_ips");

                // Policy fixtures use a separate vault after the complete normal UI proof.
                // The ephemeral normal-user journey runs separately from a fresh app.
                DiscoveryController current = discovery; MainActivity screen = activity; String appOrigin = origin;
                driver.runOnMainSync(() -> { current.paused(); screen.webViewForTesting().loadUrl(appOrigin + "/?scenario=fresh"); });
                AutomaticPcIpPolicyVerification.verify(driver, activity, origin, token, fleet, target);
                stage(driver, "automatic_ip_policy_preservation");
                result.putString("stream", "PASS: actual native search→SSH trust→remembered credentials→normal Inspect + automatic IP review→"
                        + "three arbitrary-name node discoveries→saved fleet→automatic PC egress/interface reading→three rendered IP cells; "
                        + "no repair tap, full monitoring, wallet permission, sudo or node/GPU grant. "
                        + "Encrypted-store checks preserve healthy targets, manual pauses, revocation, source waits, and real SSH identity/credential denials; "
                        + "clean legacy inspection conversion and current fleet association are verified. "
                        + "Generated loopback SSH fixture only; no operator PC or existing private keys.\n");
            }
            outcome = Activity.RESULT_OK;
        } catch (Exception | AssertionError failure) {
            result.putString("stream", "FAIL (automatic PC IP): " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + "\n");
        } finally {
            try {
                if (discovery != null) { DiscoveryController ending = discovery; driver.runOnMainSync(ending::paused); }
                if (activity != null && originalClient != null && origin != null) {
                    MainActivity ending = activity; android.webkit.WebViewClient restore = originalClient; String appOrigin = origin;
                    driver.runOnMainSync(() -> { ending.webViewForTesting().loadUrl(appOrigin + "/?scenario=fresh"); ending.webViewForTesting().setWebViewClient(restore); });
                }
                if (origin != null && token != null) for (String fleet : fleets)
                    try { request(origin, token, "/mobile/fleets", new JSONObject().put("op", "delete").put("id", fleet)); } catch (Exception ignored) { }
                if (store != null && profile != null) {
                    try { store.forgetCredentials(); store.deleteProfile(profile); } catch (Exception ignored) { }
                }
                if (activity != null) for (String pc : pcs) clearPcCache(activity, pc);
            } finally {
                if (BuildConfig.DEBUG) DiscoveryNetwork.fixtureEndpointForTesting(0);
            }
        }
        driver.finish(outcome, result);
    }

    private static void stage(Instrumentation driver, String name) {
        Bundle progress = new Bundle(); progress.putString("stream", "PASS_STAGE: " + name + "\n"); driver.sendStatus(0, progress);
    }

    /** Wait for emulator startup to supply the same real transport the app requires. */
    private static void awaitDiscoveryNetwork(Instrumentation driver) throws Exception {
        android.net.ConnectivityManager manager = driver.getTargetContext().getSystemService(android.net.ConnectivityManager.class);
        long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
        String previous = null; long unchangedSince = 0;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.net.Network active = manager.getActiveNetwork();
            android.net.NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
            android.net.LinkProperties links = active == null ? null : manager.getLinkProperties(active);
            boolean eligible = capabilities != null && links != null && !links.getLinkAddresses().isEmpty()
                    && (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN));
            String snapshot = eligible ? active.toString() + "|" + links + "|"
                    + capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) + "|"
                    + capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) : null;
            long now = android.os.SystemClock.elapsedRealtime();
            if (snapshot == null || !snapshot.equals(previous)) { previous = snapshot; unchangedSince = now; }
            else if (now - unchangedSince >= 1500) {
                try (DiscoveryNetwork network = DiscoveryNetwork.capture(driver.getTargetContext())) {
                    network.ensureActive(); return;
                } catch (java.io.IOException notReady) { previous = null; unchangedSince = now; }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Disposable emulator did not supply a stable active Wi-Fi/VPN within 60 seconds");
    }

    private static void verifyFixtureRestriction(Instrumentation driver, int port) throws Exception {
        long started = DiscoveryNetwork.startedConnectionsForTesting();
        try (DiscoveryNetwork network = DiscoveryNetwork.capture(driver.getTargetContext())) {
            String[] forbiddenAddresses = {"10.0.2.2", "192.0.2.1", "127.0.0.1"};
            int[] forbiddenPorts = {22, port, port};
            for (int index = 0; index < forbiddenAddresses.length; index++) {
                try {
                    network.connect(forbiddenAddresses[index], forbiddenPorts[index], 100);
                    throw new AssertionError("Debug fixture restriction allowed another endpoint");
                } catch (java.io.IOException expected) {
                    require("Connection is outside the generated emulator fixture".equals(expected.getMessage()),
                            "Fixture restriction did not reject before transport work");
                }
            }
        }
        require(DiscoveryNetwork.startedConnectionsForTesting() == started,
                "Rejected fixture endpoints incremented real connection counters");
    }

    private static void discover(Instrumentation driver, DiscoveryController discovery, String profileName,
            int port, boolean remember, boolean priorTrust) throws Exception {
        awaitDiscoveryNetwork(driver);
        driver.runOnMainSync(() -> discovery.show(null));
        require(clickText(driver, "Search network"), "Could not choose normal Search network; " + visibleText(driver));
        require(clickText(driver, profileName), "Could not select generated fixture profile");
        require(clickText(driver, "Search"), "Could not confirm the bounded native search");
        awaitText(driver, "Select computers to inspect");
        require(clickContainingText(driver, "10.0.2.2:" + port), "Could not select discovered SSH fixture; " + visibleText(driver));
        require(clickText(driver, "Review SSH identities"), "Could not review normal SSH identity");
        if (!priorTrust) require(clickText(driver, "Trust this key"), "Could not approve generated SSH identity; " + visibleText(driver));
        require(clickText(driver, "Enter / import SSH credentials"), "Could not enter native credentials; " + visibleText(driver));
        awaitText(driver, "SSH credentials");
        require(setField(driver, "SSH username", "fixture"), "Could not fill generated SSH username");
        require(setField(driver, "SSH password (leave blank for key login)", "isolated-ssh-password"), "Could not fill generated SSH password");
        if (!remember) require(clickText(driver, "Remember encrypted credentials"), "Could not select ephemeral credential mode");
        require(clickText(driver, "Continue"), "Could not submit generated native credential");
        awaitText(driver, "Review 1 selected, trusted PCs");
        require(hasText(driver, "remembered") && hasText(driver, "ipify"), "Automatic IP review did not explain retained-credential/IP authority");
        require(clickText(driver, "Inspect + automatic IP"), "Normal review lacks its automatic PC-IP action; " + visibleText(driver));
    }

    private static DiscoveryController awaitDiscovery(Instrumentation driver, MainActivity activity) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            DiscoveryController value = (DiscoveryController) field(activity, "discovery"); if (value != null) return value;
            Thread.sleep(100);
        }
        throw new AssertionError("Native discovery controller did not start");
    }
    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field found = owner.getClass().getDeclaredField(name); found.setAccessible(true); return found.get(owner);
    }
    private static JSONObject awaitTarget(DiscoveryStore store, String fleet) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            JSONArray targets = store.monitoringTargets();
            for (int i = 0; i < targets.length(); i++) if (fleet.equals(targets.getJSONObject(i).optString("fleetId"))) return targets.getJSONObject(i);
            Thread.sleep(100);
        }
        throw new AssertionError("Normal discovery saved no automatic PC-IP target");
    }
    private static JSONObject awaitNetwork(String origin, String token, String fleet) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            JSONObject value = request(origin, token, "/mobile/observations?fleet=" + fleet, null);
            if (value.getJSONArray("pcNetworks").length() == 1) return value;
            Thread.sleep(100);
        }
        throw new AssertionError("Ordinary discovery saved a target but automatic PC-IP collection produced no observation");
    }
    private static JSONObject awaitRuntime(Instrumentation driver) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            JSONObject value = NodeRuntime.ready(driver.getTargetContext()); if (value != null) return value; Thread.sleep(100);
        }
        throw new AssertionError("Embedded runtime did not start");
    }
    private static void clearPcCache(android.content.Context context, String pc) {
        android.content.SharedPreferences preferences = context.getSharedPreferences("pc-network-observations", android.content.Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) if (key.startsWith(pc)) editor.remove(key);
        require(editor.commit(), "Generated fixture cache cleanup failed");
    }
    private static void awaitJavascript(Instrumentation driver, MainActivity activity, String expression) throws Exception {
        awaitJavascript(driver, activity, expression, 20000);
    }
    private static void awaitJavascript(Instrumentation driver, MainActivity activity, String expression, long allowanceMs) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + allowanceMs;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(javascript(driver, activity, expression))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Automatic IP WebView condition failed: " + expression);
    }
    private static String javascript(Instrumentation driver, MainActivity activity, String expression) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> value = new java.util.concurrent.atomic.AtomicReference<>();
        driver.runOnMainSync(() -> activity.webViewForTesting().evaluateJavascript(expression, answer -> { value.set(answer); done.countDown(); }));
        require(done.await(5, java.util.concurrent.TimeUnit.SECONDS), "Automatic IP WebView JavaScript timed out"); return value.get();
    }
    private static void awaitText(Instrumentation driver, String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 45000;
        while (!hasText(driver, text) && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100);
        require(hasText(driver, text), "Native text missing: " + text + "; visible=" + visibleText(driver));
    }
    private static boolean hasText(Instrumentation driver, String expected) {
        return visit(driver.getUiAutomation().getRootInActiveWindow(), node -> node.getText() != null && node.getText().toString().contains(expected));
    }
    private interface NodeCheck { boolean test(AccessibilityNodeInfo node); }
    private static boolean visit(AccessibilityNodeInfo node, NodeCheck test) {
        if (node == null) return false;
        try {
            if (test.test(node)) return true;
            for (int i = 0; i < node.getChildCount(); i++) if (visit(node.getChild(i), test)) return true;
            return false;
        } finally { node.recycle(); }
    }
    private static boolean clickText(Instrumentation driver, String expected) throws Exception {
        return clickMatching(driver, node -> node.getText() != null && node.getText().toString().equalsIgnoreCase(expected));
    }
    private static boolean clickContainingText(Instrumentation driver, String expected) throws Exception {
        return clickMatching(driver, node -> node.getText() != null && node.getText().toString().contains(expected));
    }
    private static boolean clickMatching(Instrumentation driver, NodeCheck matching) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            if (visit(driver.getUiAutomation().getRootInActiveWindow(), node -> {
                if (!matching.test(node) || !node.isVisibleToUser()) return false;
                node.getBoundsInScreen(bounds); return !bounds.isEmpty();
            })) {
                long down = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0);
                android.view.MotionEvent release = android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
                try { driver.sendPointerSync(press); driver.sendPointerSync(release); } finally { press.recycle(); release.recycle(); }
                driver.waitForIdleSync();
                Thread.sleep(100);
                return true;
            }
            visit(driver.getUiAutomation().getRootInActiveWindow(), node -> node.isScrollable()
                    && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            Thread.sleep(100);
        }
        return false;
    }
    private static boolean setField(Instrumentation driver, String hint, String value) throws Exception {
        Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        long deadline = android.os.SystemClock.elapsedRealtime() + 15000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (visit(driver.getUiAutomation().getRootInActiveWindow(), node -> {
                if (!"android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                        || !(hint.contentEquals(node.getHintText() == null ? "" : node.getHintText())
                            || hint.contentEquals(node.getText() == null ? "" : node.getText()))) return false;
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            })) { driver.waitForIdleSync(); return true; }
            visit(driver.getUiAutomation().getRootInActiveWindow(), node -> node.isScrollable()
                    && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD));
            Thread.sleep(100);
        }
        return false;
    }
    private static String visibleText(Instrumentation driver) {
        StringBuilder text = new StringBuilder();
        visit(driver.getUiAutomation().getRootInActiveWindow(), node -> {
            if (!node.isPassword() && !"android.widget.EditText".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                    && node.getText() != null && text.length() < 1800) text.append(node.getText()).append(" | ");
            return false;
        });
        return text.toString();
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
            require(connection.getResponseCode() == 200, "Automatic IP fixture API request failed");
            try (InputStream input = connection.getInputStream()) {
                return new JSONObject(new String(NodeRuntime.readBounded(input, 512 * 1024), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private AutomaticPcIpVerification() { }
}
