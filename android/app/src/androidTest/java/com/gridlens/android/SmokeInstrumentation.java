package com.gridlens.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the real embedded Android library against only the bundled synthetic Fleet scenario. */
public final class SmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        MainActivity activity = null;
        try {
            require(!new java.io.File(getTargetContext().getFilesDir(), "private-data/hosts.json").exists()
                && !new java.io.File(getTargetContext().getFilesDir(), "private-data/real200.json").exists(),
                "Smoke test requires cleared emulator application data; existing rosters are never overwritten");
            Intent launch = new Intent(getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh");
            activity = (MainActivity) startActivitySync(launch);
            final MainActivity current = activity;
            JSONObject page = null;
            long deadline = android.os.SystemClock.elapsedRealtime() + 60000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                String snapshot = javascript(current,
                    "JSON.stringify({rows:document.querySelectorAll('tbody tr').length,url:location.href,text:document.body.innerText})");
                if (snapshot != null && !"null".equals(snapshot)) {
                    Object decoded = new JSONTokener(snapshot).nextValue();
                    if (decoded instanceof String) {
                        page = new JSONObject((String) decoded);
                        if (page.optInt("rows") >= 8 && page.optString("url").contains("scenario=fresh")) break;
                    }
                }
                Thread.sleep(150);
            }
            require(page != null && page.optInt("rows") >= 8, "Synthetic Fleet did not render eight hosts");
            require(page.getString("url").startsWith("http://127.0.0.1:"), "Fleet is not running on phone loopback");
            require(page.getString("url").contains("scenario=fresh"), "Test unexpectedly selected a live scenario");
            require(!page.getString("text").contains("could not load"), "Runtime startup failed");
            JSONObject ready = NodeRuntime.ready(getTargetContext());
            require(ready != null, "Runtime did not publish its private readiness file");
            String origin = "http://127.0.0.1:" + ready.getInt("port");
            require(request(origin + "/", null, null).status == 403, "Unauthenticated loopback read was accepted");
            String token = ready.getString("token");
            require(request(origin + "/", token, null).status == 200, "Authenticated loopback read failed");
            // POST exercises the actual embedded roster persistence path. Synthetic UI remains selected.
            Reply imported = request(origin + "/mobile/rosters/supplied", token, "[\"11111111111111111111111111111111\"]");
            require(imported.status == 200 && new JSONObject(imported.body).getInt("count") == 1, "Native roster import failed");
            Reply invalid = request(origin + "/mobile/rosters/supplied", token, "[\"invalid\"]");
            require(invalid.status >= 400, "Invalid roster was accepted");
            // Clean up the synthetic test roster. Never leave fixture addresses in normal app data.
            require(request(origin + "/mobile/rosters/supplied", token, "[]").status == 200, "Could not clear test roster");
            runOnMainSync(() -> { callActivityOnPause(current); callActivityOnResume(current); });
            waitForIdleSync();
            String resumed = javascript(current, "JSON.stringify({rows:document.querySelectorAll('tbody tr').length,url:location.href})");
            JSONObject resumedPage = new JSONObject((String) new JSONTokener(resumed).nextValue());
            require(resumedPage.getInt("rows") >= 8, "Lifecycle resume lost Fleet");
            verifyUpdaterRejections(current);
            result.putString("stream", "PASS: embedded Node/WebView boot, eight synthetic hosts, loopback authentication, roster validation/persistence, pause/resume, actual Android APK rejection and narrow update provider. No live provider or GitHub requests.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("stream", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Exercises the real package parser and provider without downloading or installing anything. */
    private void verifyUpdaterRejections(MainActivity activity) throws Exception {
        UpdateController controller = new UpdateController(activity);
        File malformed = File.createTempFile("invalid-update-", ".apk", getTargetContext().getCacheDir());
        File updates = new File(getTargetContext().getCacheDir(), "updates");
        File providerFile = new File(updates, "update.apk");
        require(!providerFile.exists(), "Updater tests require a cleared emulator cache; existing downloads are never overwritten");
        try {
            UpdatePolicy.Release expected = new UpdatePolicy.Release(1, BuildConfig.VERSION_CODE + 1L,
                "0.0.999", "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.0.999/gridlens-android.apk",
                "0000000000000000000000000000000000000000000000000000000000000000", 1, 26);
            try (FileOutputStream output = new FileOutputStream(malformed)) {
                output.write("This is not an APK".getBytes(StandardCharsets.UTF_8));
            }
            rejects(() -> controller.verifyArchive(malformed, expected), "Android could not verify the downloaded APK.");
            // This is a real, valid APK with real signing certificates, but its version is not the offered update.
            rejects(() -> controller.verifyArchive(new File(getTargetContext().getApplicationInfo().sourceDir), expected),
                "The update version did not match or is not newer.");
            // The instrumentation APK is signed for this emulator, but it is a different Android package.
            rejects(() -> controller.verifyArchive(new File(getContext().getApplicationInfo().sourceDir), expected),
                "The download is not a GridLens update.");

            require(updates.isDirectory() || updates.mkdirs(), "Cannot prepare isolated provider fixture");
            try (FileOutputStream output = new FileOutputStream(providerFile)) { output.write(42); }
            String authority = "content://" + getTargetContext().getPackageName() + ".updates";
            try (InputStream input = getTargetContext().getContentResolver().openInputStream(Uri.parse(authority + "/update.apk"))) {
                require(input != null && input.read() == 42, "Granted update URI did not open its one expected file");
            }
            for (String path : new String[]{"/other.apk", "/../update.apk", "/%75pdate.apk", "/update.apk?path=other"}) {
                rejects(() -> {
                    try (InputStream ignored = getTargetContext().getContentResolver().openInputStream(Uri.parse(authority + path))) {
                        throw new AssertionError("Update provider accepted an unapproved URI");
                    }
                }, null);
            }
            rejects(() -> {
                try (android.os.ParcelFileDescriptor ignored = getTargetContext().getContentResolver()
                        .openFileDescriptor(Uri.parse(authority + "/update.apk"), "w")) {
                    throw new AssertionError("Update provider accepted a write");
                }
            }, null);
        } finally {
            malformed.delete();
            providerFile.delete();
            controller.destroy();
        }
    }

    private interface CheckedAction { void run() throws Exception; }
    private static void rejects(CheckedAction action, String expectedMessage) throws Exception {
        try { action.run(); }
        catch (Exception error) {
            if (expectedMessage != null) require(expectedMessage.equals(error.getMessage()), "Unexpected update rejection: " + error.getClass().getSimpleName());
            return;
        }
        throw new AssertionError("Unsafe update input was accepted");
    }

    private String javascript(MainActivity activity, String expression) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>();
        runOnMainSync(() -> activity.webViewForTesting().evaluateJavascript(expression, value -> { answer.set(value); completed.countDown(); }));
        if (!completed.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView JavaScript timed out");
        return answer.get();
    }

    private static final class Reply {
        final int status;
        final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }

    private Reply request(String url, String token, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        if (token != null) connection.setRequestProperty("X-GridLens-Token", token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return new Reply(status, input == null ? "" : new String(NodeRuntime.readBounded(input, 256 * 1024), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
