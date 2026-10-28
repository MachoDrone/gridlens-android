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

/** Full smoke by default; explicit native-only and background-download fixtures remain separate. */
public final class SmokeInstrumentation extends Instrumentation {
    private boolean nativeOnly;
    private boolean backgroundDownload;
    private Bundle productionSsh;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        nativeOnly = arguments != null && "true".equals(arguments.getString("nativeOnly"));
        backgroundDownload = arguments != null && ("true".equals(arguments.getString("backgroundDownload"))
                || "true".equals(arguments.getString("downloadOnly")));
        productionSsh = arguments != null && "true".equals(arguments.getString("productionSsh")) ? arguments : null;
        start();
    }

    @Override public void onStart() {
        if (productionSsh != null) { ProductionNetworkInstrumentation.verify(this, productionSsh); return; }
        Bundle result = new Bundle();
        MainActivity activity = null;
        try {
            require(!(nativeOnly && backgroundDownload), "Choose either nativeOnly or backgroundDownload/downloadOnly");
            if (backgroundDownload) {
                require(BuildConfig.DEBUG, "Background download verification requires a debug fixture app");
                require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                    "Background download verification requires zero prior discovery/SSH activity");
                UpdateDownloadVerification.verifyBackground(this);
                require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                    "Background download verification unexpectedly started discovery/SSH");
                result.putString("stream", "PASS (background-download only): foreground-service transfer advanced after Activity destruction/Home, reattached to the same job with downloaded/total MB and percent, and reached verified READY. Zero installer launches; installed APK version unchanged. No external download requests or PC discovery/SSH connections. Full Fleet smoke and physical-device behavior were not tested.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (nativeOnly) {
                require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                    "Native-only verification requires zero prior discovery/SSH activity");
                DiscoveryVerification.verify(getTargetContext());
                MonitoringVerification.verify(getTargetContext());
                UpdateDownloadVerification.verifyStore(getTargetContext());
                require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                    "Native-only vault checks unexpectedly started network discovery/SSH");
                result.putString("stream", "PASS: native Keystore, monitoring permissions and isolated update-store checks only; zero native discovery/SSH connections. No Activity or WebView launched. UI, embedded runtime, background APK transfer, installation and physical PCs were not tested.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
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
                    "JSON.stringify({rows:document.querySelectorAll('tbody tr').length,url:location.href,text:document.body?.innerText ?? ''})");
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
            verifyUpdatePresentation(current);
            verifyDownloadPresentation(current);
            verifyDownloadServiceManifest();
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
            DiscoveryVerification.verify(getTargetContext());
            MonitoringVerification.verify(getTargetContext());
            UpdateDownloadVerification.verifyStore(getTargetContext());
            require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                "Launch or ordinary Fleet operations started network discovery/SSH");
            PcNetworkVerification.verify(this, current, origin, token);
            SavedTargetIpVerification.verify(this, current, origin, token);
            runOnMainSync(() -> current.webViewForTesting().loadUrl(origin + "/?scenario=network-discovery"));
            long discoveryDeadline = android.os.SystemClock.elapsedRealtime() + 15000;
            boolean discoveryReady = false;
            while (android.os.SystemClock.elapsedRealtime() < discoveryDeadline) {
                String readyPage = javascript(current, "document.querySelector('#mobile-network-search')?.textContent || ''");
                if ("\"Search network\"".equals(readyPage)) { discoveryReady = true; break; }
                Thread.sleep(100);
            }
            require(discoveryReady, "Android discovery landing screen did not load");
            javascript(current, "document.getElementById('refresh-view').click()");
            runOnMainSync(() -> { callActivityOnPause(current); callActivityOnResume(current); });
            require(DiscoveryNetwork.startedSearchesForTesting() == 0 && DiscoveryNetwork.startedConnectionsForTesting() == 0,
                "Opening/resuming/refreshing Network Discovery started a search");
            result.putString("stream", "PASS: embedded Node/WebView boot, eight synthetic hosts, loopback authentication, roster validation/persistence, pause/resume, installed-version/update-status presentation at 390dp, download MB/total/percent presentation, private service declaration and isolated update-store recovery, actual Android APK rejection and narrow update provider. Independent PC IP controller/API/WebView checks passed with three blocked node workers, shared PC readings, malformed GPU rejection and foreground/revocation cancellation. No live provider or GitHub requests.\n");
            finish(Activity.RESULT_OK, result);
        } catch (Exception | AssertionError error) {
            result.putString("stream", (backgroundDownload ? "FAIL (background-download only): " : nativeOnly ? "FAIL (native-only): " : "FAIL: ") + error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    /** Synthetic policy transitions exercise native text/color without checking GitHub. */
    private void verifyUpdatePresentation(MainActivity activity) {
        runOnMainSync(() -> {
            android.view.View decor = activity.getWindow().getDecorView();
            android.widget.TextView version = decor.findViewWithTag("app-version");
            android.widget.TextView status = decor.findViewWithTag("app-update-status");
            android.widget.Button action = decor.findViewWithTag("app-update-action");
            android.view.View brand = decor.findViewWithTag("app-brand");
            android.view.View toolbar = decor.findViewWithTag("app-toolbar");
            require(version != null && status != null && action != null, "Installed version or update status is missing");
            require(version.getText().toString().equals("v" + BuildConfig.VERSION_NAME), "Toolbar version is not the installed APK version");
            require(brand.getContentDescription().toString().contains(BuildConfig.VERSION_NAME), "Installed version is not accessible");
            require(status.getVisibility() == android.view.View.VISIBLE, "Runtime completion hid update status");
            UpdateAvailability.State original = activity.updateStateForTesting();
            require(original != null, "Updater did not supply its initial status");
            UpdateAvailability fixture = new UpdateAvailability(0, null, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT);
            android.graphics.drawable.Drawable originalBackground = action.getBackground();
            long checkedAt = System.currentTimeMillis() - 60000;
            try {
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().equals(activity.getString(R.string.update_status_not_checked)), "Initial update status is not visible");
                verifyUpdateButtonContrast(action, false, originalBackground);
                fixture.attempted(checkedAt);
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().equals(activity.getString(R.string.update_status_checking)), "Checking status is not visible");
                verifyUpdateButtonContrast(action, false, originalBackground);
                UpdatePolicy.Release newer = new UpdatePolicy.Release(1, BuildConfig.VERSION_CODE + 1L, "99.0.0",
                        "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v99.0.0/gridlens-android.apk", "a".repeat(64), 123, 26);
                UpdateAvailability undated = new UpdateAvailability(0, newer, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT);
                activity.updateStateForTesting(undated.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().equals(activity.getString(R.string.update_status_available_undated, "99.0.0")), "Migrated availability invented a successful check time");
                fixture.accept(newer, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT, checkedAt);
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().contains("99.0.0") && status.getText().toString().contains("checked"), "Available version/check time is missing");
                require(action.getBackgroundTintList() != null && action.getBackgroundTintList().getDefaultColor() == android.graphics.Color.rgb(16, 232, 12), "A verified available update is not green");
                verifyUpdateButtonContrast(action, true, originalBackground);
                require(version.getText().toString().equals("v" + BuildConfig.VERSION_NAME), "Available release replaced the installed-version label");
                fixture.failed();
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().contains("Check failed") && status.getText().toString().contains("99.0.0"), "Failed check hides retained update evidence");
                require(action.getBackgroundTintList().getDefaultColor() == android.graphics.Color.rgb(16, 232, 12), "Failed check erased a verified update highlight");
                verifyUpdateButtonContrast(action, true, originalBackground);
                UpdatePolicy.Release current = new UpdatePolicy.Release(1, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                        "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v" + BuildConfig.VERSION_NAME + "/gridlens-android.apk", "b".repeat(64), 123, 26);
                fixture.accept(current, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT, checkedAt + 1);
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().contains("Up to date") && status.getText().toString().contains("checked"), "Last successful up-to-date check is not visible");
                require(!status.getText().toString().contains("99.0.0"), "Successful latest-release check retained withdrawn availability text");
                verifyUpdateButtonContrast(action, false, originalBackground);
                fixture.failed();
                activity.updateStateForTesting(fixture.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().contains("Check failed"), "Failure without an available update is not explicit");
                verifyUpdateButtonContrast(action, false, originalBackground);
                UpdateAvailability failedFirstCheck = new UpdateAvailability(0, null, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT);
                failedFirstCheck.failed();
                activity.updateStateForTesting(failedFirstCheck.snapshot(BuildConfig.VERSION_NAME));
                require(status.getText().toString().equals(activity.getString(R.string.update_status_failed)), "First failed check does not explain retrying");
                verifyUpdateButtonContrast(action, false, originalBackground);
                int width = Math.round(390 * activity.getResources().getDisplayMetrics().density);
                int left = toolbar.getLeft(), top = toolbar.getTop(), right = toolbar.getRight(), bottom = toolbar.getBottom();
                toolbar.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
                toolbar.layout(0, 0, width, toolbar.getMeasuredHeight());
                try {
                    for (String tag : new String[]{"app-fleets-action", "app-hosts-action", "app-update-action"}) {
                        android.view.View button = decor.findViewWithTag(tag);
                        require(button != null && button.getVisibility() == android.view.View.VISIBLE && button.getLeft() >= brand.getRight()
                                && button.getRight() <= width && button.getWidth() > 0, "Toolbar action is clipped at 390dp");
                    }
                    require(version.getLayout() != null && version.getLayout().getEllipsisCount(0) == 0, "Installed version is clipped at 390dp");
                } finally {
                    toolbar.measure(android.view.View.MeasureSpec.makeMeasureSpec(right - left, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(bottom - top, android.view.View.MeasureSpec.EXACTLY));
                    toolbar.layout(left, top, right, bottom); toolbar.requestLayout();
                }
            } finally { activity.updateStateForTesting(original); }
        });
    }

    /** Display-only transfer fixtures cannot start a download or launch Android's installer. */
    private void verifyDownloadPresentation(MainActivity activity) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
                android.view.View decor = activity.getWindow().getDecorView();
                android.widget.TextView status = decor.findViewWithTag("app-update-status");
                android.widget.TextView version = decor.findViewWithTag("app-version");
                android.widget.Button action = decor.findViewWithTag("app-update-action");
                UpdateDownloadState originalDownload = activity.downloadStateForTesting();
                UpdateAvailability.State originalAvailability = activity.updateStateForTesting();
                try {
                    UpdateDownloadState downloading = new UpdateDownloadState("fixture-transfer", "downloading", "99.0.0", 50_000_000, 200_000_000, "");
                    activity.downloadStateForTesting(downloading);
                    require(status.getText().toString().contains("50.00 / 200.00 MB · 25%"), "Download progress lost downloaded MB, total MB or percentage");
                    require(action.getContentDescription().toString().contains("view progress or cancel"), "Update action does not explain active download controls");
                    activity.updateStateForTesting(new UpdateAvailability(0, null, BuildConfig.VERSION_CODE, android.os.Build.VERSION.SDK_INT).snapshot(BuildConfig.VERSION_NAME));
                    require(status.getText().toString().contains(downloading.progressText()), "Availability refresh replaced download progress");
                    activity.downloadStateForTesting(new UpdateDownloadState("fixture-transfer", "downloading", "99.0.0", 100_000_000, 200_000_000, ""));
                    require(status.getText().toString().contains("100.00 / 200.00 MB · 50%"), "Progress update lost its original total size");
                    activity.downloadStateForTesting(new UpdateDownloadState("fixture-transfer", "verifying", "99.0.0", 200_000_000, 200_000_000, ""));
                    require(status.getText().toString().contains("Verifying") && status.getText().toString().contains("200.00 / 200.00 MB · 100%"), "Verification hid the downloaded size or claimed installation");
                    activity.downloadStateForTesting(new UpdateDownloadState("fixture-transfer", "ready", "99.0.0", 200_000_000, 200_000_000, ""));
                    require(status.getText().toString().contains("tap Update to install") && status.getText().toString().contains("200.00 / 200.00 MB · 100%"), "Completed download does not retain size and explicit installation action");
                    require(version.getText().toString().equals("v" + BuildConfig.VERSION_NAME), "A downloaded version replaced the installed-version label");
                    for (String state : new String[]{"failed", "cancelled", "interrupted"}) {
                        activity.downloadStateForTesting(new UpdateDownloadState("fixture-transfer", state, "99.0.0", 50_000_000, 200_000_000, "Fixture outcome"));
                        require(status.getText().toString().contains(state) && status.getText().toString().contains("50.00 / 200.00 MB · 25%"), "Transfer outcome lost its progress evidence");
                    }
                } finally {
                    activity.updateStateForTesting(originalAvailability);
                    activity.downloadStateForTesting(originalDownload);
                }
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Download presentation: " + failure.get().getMessage(), failure.get());
    }

    private void verifyDownloadServiceManifest() throws Exception {
        android.content.pm.ServiceInfo service = getTargetContext().getPackageManager().getServiceInfo(
                new android.content.ComponentName(getTargetContext(), "com.gridlens.android.UpdateDownloadService"), 0);
        require(!service.exported, "Update download service is externally startable");
        require((service.flags & android.content.pm.ServiceInfo.FLAG_STOP_WITH_TASK) == 0, "Closing the Activity task stops an approved download");
        if (android.os.Build.VERSION.SDK_INT >= 29)
            require(service.getForegroundServiceType() == android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, "Update service requests unrelated foreground capabilities");
    }

    private static void verifyUpdateButtonContrast(android.widget.Button button, boolean available,
            android.graphics.drawable.Drawable originalBackground) {
        require(button.isEnabled() && button.getVisibility() == android.view.View.VISIBLE && button.getAlpha() > 0.99f,
                "Update action is not visibly enabled");
        require(button.getText().toString().equals(button.getContext().getString(R.string.update_button)), "Update caption is missing");
        require(button.getContentDescription() != null && button.getContentDescription().length() > 0, "Update action has no accessible description");
        require(button.getBackground() == originalBackground, "Update styling replaced the native drawable/ripple");
        android.content.res.ColorStateList tint = button.getBackgroundTintList();
        require(tint != null, "Update button has no explicit background tint");
        int background = tint.getColorForState(button.getDrawableState(), tint.getDefaultColor());
        int foreground = button.getCurrentTextColor();
        require(background == (available ? android.graphics.Color.rgb(16, 232, 12) : android.graphics.Color.rgb(89, 89, 89)), "Update background does not match its availability state");
        require(foreground == (available ? android.graphics.Color.BLACK : android.graphics.Color.WHITE), "Update caption color does not match its background");
        require(android.graphics.Color.alpha(background) == 255 && android.graphics.Color.alpha(foreground) == 255, "Update colors must remain opaque");
        double light = Math.max(luminance(background), luminance(foreground));
        double dark = Math.min(luminance(background), luminance(foreground));
        require((light + 0.05) / (dark + 0.05) >= 4.5, "Update caption has insufficient contrast");
    }
    private static double luminance(int color) {
        return 0.2126 * linearChannel(android.graphics.Color.red(color))
                + 0.7152 * linearChannel(android.graphics.Color.green(color))
                + 0.0722 * linearChannel(android.graphics.Color.blue(color));
    }
    private static double linearChannel(int value) {
        double scaled = value / 255.0;
        return scaled <= 0.04045 ? scaled / 12.92 : Math.pow((scaled + 0.055) / 1.055, 2.4);
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
