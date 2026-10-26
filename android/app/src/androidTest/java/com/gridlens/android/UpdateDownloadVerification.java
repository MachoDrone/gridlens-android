package com.gridlens.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

/** Disposable update fixtures. Never downloads from GitHub or installs the supplied APK. */
final class UpdateDownloadVerification {
    static void verifyStore(Context context) throws Exception {
        String namespace = "update-download-verification";
        File journal = new File(context.getNoBackupFilesDir(), namespace + "-update-download.json");
        require(!journal.exists(), "Unused update fixture namespace required");
        UpdateDownloadStore store = new UpdateDownloadStore(context, namespace);
        byte[] bytes = new byte[]{1, 2, 3, 4};
        StringBuilder hash = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
        UpdatePolicy.Release release = new UpdatePolicy.Release(1, 999, "0.0.999",
                "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.0.999/gridlens-android.apk", hash.toString(), bytes.length, 26);
        UpdateDownloadStore.Record old = store.begin(release);
        store.update(old.state.id, "cancelled", 0, "Fixture cancellation");
        UpdateDownloadStore.Record next = store.begin(release);
        try {
            require(!store.partial(old.state.id).equals(store.partial(next.state.id)), "Jobs share a partial path");
            Files.write(store.partial(old.state.id).toPath(), new byte[]{9});
            Files.write(store.partial(next.state.id).toPath(), bytes);
            require(!store.promoteVerified(old.state.id), "A cancelled old job promoted its file");
            require(!store.update(old.state.id, "failed", 0, "Stale worker"), "A stale worker replaced the active record");
            require(store.partial(old.state.id).delete(), "Could not delete old fixture partial");
            require(java.util.Arrays.equals(bytes, Files.readAllBytes(store.partial(next.state.id).toPath())), "Old cleanup damaged the new partial");
            store.update(next.state.id, "verifying", bytes.length, "Fixture verified");
            require(store.promoteVerified(next.state.id), "Current fixture promotion failed");
            UpdateDownloadStore reopened = new UpdateDownloadStore(context, namespace);
            require(reopened.read().state.ready() && next.state.id.equals(reopened.read().state.id), "Completed transfer did not reattach");
            require(!reopened.promoteVerified(old.state.id), "Late old promotion replaced a ready update");
            require(java.util.Arrays.equals(bytes, Files.readAllBytes(reopened.apk().toPath())), "Ready fixture content changed");
            require(reopened.apk().delete(), "Could not simulate removed fixture file");
            reopened.invalidateMissingReadyFile();
            require("failed".equals(reopened.read().state.status), "Missing APK was still presented as ready");
        } finally {
            UpdateDownloadStore.Record record = store.read();
            if (record != null && record.state.active()) store.update(record.state.id, "cancelled", record.state.downloadedBytes, "Fixture cleanup");
            store.clearFixture(next.state.id);
            File directory = store.apk().getParentFile(); if (directory.exists()) require(directory.delete(), "Fixture directory cleanup failed");
        }
    }

    static void verifyBackground(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        File apk = new File(context.getCacheDir(), "background-transfer-fixture.apk");
        File manifest = new File(context.getCacheDir(), "background-transfer-fixture.json");
        require(BuildConfig.DEBUG && apk.isFile() && manifest.isFile(), "Push the isolated debug APK and manifest fixtures before this check");
        UpdatePolicy.Release release;
        try (InputStream input = new FileInputStream(manifest)) { release = UpdateReleaseJson.parse(new JSONObject(new String(NodeRuntime.readBounded(input, 16 * 1024), StandardCharsets.UTF_8))); }
        require("0.0.999".equals(release.versionName) && release.isNewerThan(BuildConfig.VERSION_CODE), "A newer synthetic APK is required");
        UpdateArchiveVerifier.verify(context, apk, release);
        long installed = installedVersion(context);
        Instrumentation.ActivityMonitor installer = instrumentation.addMonitor(new IntentFilter(Intent.ACTION_INSTALL_PACKAGE), null, true);
        MainActivity first = null, second = null;
        String id = null;
        try {
            UpdateDownloadService.setTransportForTesting(context, release.apkUri, uri -> {
                require(uri.equals(release.apkUri), "Fixture tried an unexpected network destination");
                return new UpdateDownloadEngine.Response() {
                    InputStream input;
                    public int status() { return 200; }
                    public String location() { return null; }
                    public long contentLength() { return release.size; }
                    public InputStream body() throws Exception {
                        input = new FileInputStream(apk) {
                            @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                                try { Thread.sleep(250); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new java.io.IOException("Fixture interrupted"); }
                                return super.read(bytes, offset, Math.min(length, 32 * 1024));
                            }
                        }; return input;
                    }
                    public void close() { if (input != null) try { input.close(); } catch (Exception ignored) {} }
                };
            });
            Intent launch = new Intent(context, MainActivity.class).setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("scenario", "fresh");
            first = (MainActivity) instrumentation.startActivitySync(launch);
            final MainActivity started = first;
            final String[] startedId = new String[1];
            instrumentation.runOnMainSync(() -> {
                try { startedId[0] = UpdateDownloadService.start(started, release).id; }
                catch (Exception error) { throw new AssertionError(error); }
            });
            id = startedId[0]; require(id != null, "Transfer did not start");
            UpdateDownloadStore store = new UpdateDownloadStore(context);
            waitFor(() -> store.read() != null && store.read().state.downloadedBytes > 0, 15000, "Fixture transfer made no progress");
            long before = store.read().state.downloadedBytes;
            instrumentation.runOnMainSync(started::finish);
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            waitFor(started::isDestroyed, 10000, "The first Activity did not reach destruction");
            long backgroundBytes = store.read().state.downloadedBytes;
            require(backgroundBytes < release.size && backgroundBytes >= before, "The fixture completed before background progress could be measured");
            waitFor(() -> store.read().state.downloadedBytes > backgroundBytes, 10000, "Bytes stopped when the Activity was destroyed/backgrounded");
            second = (MainActivity) instrumentation.startActivitySync(launch);
            require(id.equals(store.read().state.id), "Reopening started a different transfer");
            MainActivity reattached = second;
            final String expectedId = id;
            waitFor(() -> displayedTransfer(instrumentation, reattached, expectedId, release.size, false), 5000,
                    "Reattached native UI did not show the same active job with downloaded/total MB and percent");
            waitFor(() -> store.read().state.ready() || "failed".equals(store.read().state.status), 45000, "Fixture transfer did not finish");
            require(store.read().state.ready(), "Fixture did not pass the real hash/package/signing checks: " + store.read().state.detail);
            require(store.read().state.downloadedBytes == release.size, "Completed byte count differs from manifest");
            waitFor(() -> displayedTransfer(instrumentation, reattached, expectedId, release.size, true), 5000,
                    "Verified completion did not reach the native ready UI with its full byte count");
            require(installer.getHits() == 0, "Completion opened an installer without an Install tap");
            require(installedVersion(context) == installed, "The synthetic APK was installed unexpectedly");
        } finally {
            if (first != null) { MainActivity value = first; instrumentation.runOnMainSync(value::finish); }
            if (second != null) { MainActivity value = second; instrumentation.runOnMainSync(value::finish); }
            if (id != null) {
                UpdateDownloadService.cancelExplicitly(context, id);
                final String finishedId = id;
                waitFor(() -> !UpdateDownloadService.isRunning(finishedId), 15000, "Fixture service did not stop");
                UpdateDownloadService.clearTransportForTesting(context, id);
            }
            instrumentation.removeMonitor(installer);
        }
    }
    private interface Check { boolean ready() throws Exception; }
    private static boolean displayedTransfer(Instrumentation instrumentation, MainActivity activity, String id, long expectedSize, boolean ready) {
        boolean[] matched = {false};
        instrumentation.runOnMainSync(() -> {
            UpdateDownloadState state = activity.downloadStateForTesting();
            android.widget.TextView label = activity.getWindow().getDecorView().findViewWithTag("app-update-status");
            matched[0] = state != null && id.equals(state.id) && state.totalBytes == expectedSize
                    && (ready ? state.ready() && state.downloadedBytes == expectedSize : state.active() && state.downloadedBytes > 0 && state.downloadedBytes < expectedSize)
                    && label != null && label.getText().toString().contains(state.progressText());
        });
        return matched[0];
    }
    private static void waitFor(Check check, long duration, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < deadline) { if (check.ready()) return; Thread.sleep(100); }
        throw new AssertionError(failure);
    }
    private static long installedVersion(Context context) throws Exception {
        android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private UpdateDownloadVerification() {}
}
