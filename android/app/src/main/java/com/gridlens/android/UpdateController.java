package com.gridlens.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded foreground availability checks; downloads and installation require user actions. */
@SuppressWarnings("deprecation")
public final class UpdateController {
    private final Activity activity;
    private final SharedPreferences preferences;
    private final UpdateAvailability availability;
    private final AvailabilityListener availabilityListener;
    private final boolean automaticChecksEnabled;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile HttpURLConnection activeConnection;
    private Future<?> task;
    private ProgressDialog progress;
    private AlertDialog message;
    private volatile boolean destroyed;
    private boolean busy;
    private boolean silentCheck;
    private boolean awaitingInstallPermission;
    private UpdatePolicy.Release pendingInstall;

    public interface AvailabilityListener { void changed(String versionName); }

    public UpdateController(Activity activity) {
        this(activity, versionName -> { }, true);
    }

    public UpdateController(Activity activity, AvailabilityListener listener, boolean automaticChecksEnabled) {
        this.activity = activity;
        this.availabilityListener = listener;
        this.automaticChecksEnabled = automaticChecksEnabled;
        preferences = activity.getSharedPreferences("updates", Activity.MODE_PRIVATE);
        UpdatePolicy.Release retained = null;
        try {
            String stored = preferences.getString("availableRelease", null);
            if (stored != null && stored.length() <= UpdatePolicy.MAX_MANIFEST_BYTES) {
                retained = parseRelease(new JSONObject(stored));
            }
        } catch (Exception ignored) { /* Invalid local metadata is never an update notification. */ }
        availability = new UpdateAvailability(preferences.getLong("lastAttemptAtMs", 0), retained,
                BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT);
        notifyAvailability();
    }

    public void checkForUpdate() {
        if (destroyed || (busy && !silentCheck)) return;
        if (silentCheck) cancel();
        check(false);
    }

    private void check(boolean silent) {
        int operation;
        if (silent) {
            busy = true;
            operation = generation.incrementAndGet();
        } else {
            operation = begin("Checking GitHub for an update…", false);
        }
        silentCheck = silent;
        availability.attempted(System.currentTimeMillis());
        preferences.edit().putLong("lastAttemptAtMs", availability.lastAttemptAtMs()).apply();
        task = worker.submit(() -> {
            try {
                UpdatePolicy.Release release = readRelease(operation);
                long installed = versionCode(installedPackage());
                post(operation, () -> {
                    finishProgress();
                    availability.accept(release, installed, Build.VERSION.SDK_INT);
                    persistAvailability();
                    notifyAvailability();
                    if (silent) return;
                    if (!release.isNewerThan(installed)) {
                        showMessage("GridLens is up to date", "Installed version: " + BuildConfig.VERSION_NAME
                                + ". No newer published Android release is available.");
                    } else if (release.minSdk > Build.VERSION.SDK_INT) {
                        showMessage("Android update required", "GridLens " + release.versionName
                                + " requires Android API " + release.minSdk + " or newer.");
                    } else {
                        message = new AlertDialog.Builder(activity)
                                .setTitle("GridLens " + release.versionName + " is available")
                                .setMessage("Download the signed update (" + ((release.size + 1048575) / 1048576)
                                        + " MB). Your host lists and settings are kept. Android will ask you to confirm installation.")
                                .setNegativeButton("Later", null)
                                .setPositiveButton("Download update", (dialog, which) -> download(release))
                                .show();
                    }
                });
            } catch (Exception error) {
                if (silent) post(operation, this::finishProgress);
                else fail(operation, error);
            }
        });
    }

    private void download(UpdatePolicy.Release release) {
        int operation = begin("Downloading GridLens " + release.versionName + "…", true);
        task = worker.submit(() -> {
            File partial = new File(activity.getCacheDir(), "updates/update.part");
            try {
                File folder = partial.getParentFile();
                if (folder == null || (!folder.isDirectory() && !folder.mkdirs())) {
                    throw new UpdateFailure("There is not enough accessible storage for the update.");
                }
                activity.revokeUriPermission(updateUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long started = SystemClock.elapsedRealtime();
                long total = 0;
                HttpURLConnection connection = open(release.apkUri, "gridlens-android.apk", operation);
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    long advertised = connection.getContentLengthLong();
                    if (advertised >= 0 && advertised != release.size) {
                        throw new UpdateFailure("The update size did not match its release manifest.");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    int lastPercent = -1;
                    while ((count = input.read(buffer)) != -1) {
                        ensureActive(operation);
                        if (SystemClock.elapsedRealtime() - started > 10 * 60_000L) {
                            throw new UpdateFailure("The update download timed out. Try again on a stable connection.");
                        }
                        total += count;
                        if (total > release.size || total > UpdatePolicy.MAX_APK_BYTES) {
                            throw new UpdateFailure("The update exceeded its expected size.");
                        }
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                        int percent = (int) (100 * total / release.size);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            post(operation, () -> { if (progress != null) progress.setProgress(percent); });
                        }
                    }
                    output.getFD().sync();
                } finally {
                    connection.disconnect();
                    activeConnection = null;
                }
                UpdatePolicy.verifyDownloaded(release, total, digest.digest());
                verifyArchive(partial, release);
                ensureActive(operation);
                File apk = apkFile();
                if (apk.exists() && !apk.delete()) throw new UpdateFailure("The previous update could not be replaced.");
                if (!partial.renameTo(apk)) throw new UpdateFailure("The downloaded update could not be saved.");
                post(operation, () -> {
                    finishProgress();
                    pendingInstall = release;
                    requestInstall();
                });
            } catch (Exception error) {
                partial.delete();
                fail(operation, error);
            }
        });
    }

    private void requestInstall() {
        if (pendingInstall == null || destroyed) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            message = new AlertDialog.Builder(activity)
                    .setTitle("Allow GridLens updates")
                    .setMessage("Android needs your permission to install updates from GridLens. Enable “Allow from this source”, then return here to confirm installation.")
                    .setNegativeButton("Later", null)
                    .setPositiveButton("Open Android settings", (dialog, which) -> {
                        try {
                            awaitingInstallPermission = true;
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (RuntimeException error) {
                            awaitingInstallPermission = false;
                            showMessage("Update not installed", "Android could not open installation settings. You can retry with the Update button.");
                        }
                    }).show();
            return;
        }
        UpdatePolicy.Release release = pendingInstall;
        int operation = begin("Verifying the signed update…", false);
        task = worker.submit(() -> {
            try {
                File apk = apkFile();
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long count = 0;
                try (InputStream input = new FileInputStream(apk)) {
                    byte[] buffer = new byte[64 * 1024];
                    int size;
                    while ((size = input.read(buffer)) != -1) {
                        ensureActive(operation);
                        count += size;
                        if (count > release.size) throw new UpdateFailure("The downloaded update changed. Please download it again.");
                        digest.update(buffer, 0, size);
                    }
                }
                UpdatePolicy.verifyDownloaded(release, count, digest.digest());
                verifyArchive(apk, release);
                post(operation, () -> {
                    finishProgress();
                    Uri uri = updateUri();
                    Intent installer = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    installer.setClipData(ClipData.newRawUri("GridLens update", uri));
                    try {
                        activity.startActivity(installer);
                        pendingInstall = null;
                        Toast.makeText(activity, "Confirm the GridLens update in Android’s installer.", Toast.LENGTH_LONG).show();
                    } catch (RuntimeException error) {
                        showMessage("Update not installed", "Android could not open its package installer. You can retry with the Update button.");
                    }
                });
            } catch (Exception error) {
                fail(operation, error);
            }
        });
    }

    public void onResume() {
        if (awaitingInstallPermission && !destroyed) {
            awaitingInstallPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) requestInstall();
            else showMessage("Update not installed", "Installation permission was not enabled. Your current GridLens app is unchanged.");
            return;
        }
        if (destroyed || busy || !automaticChecksEnabled) return;
        boolean due = availability.shouldCheck(System.currentTimeMillis());
        preferences.edit().putLong("lastAttemptAtMs", availability.lastAttemptAtMs()).apply();
        if (due) check(true);
    }

    public void onPause() {
        // Availability requests belong to a visible app. An explicitly requested download keeps
        // its existing lifetime and Android's mandatory confirmation flow.
        if (silentCheck) cancel();
    }

    public void destroy() {
        destroyed = true;
        cancel();
        worker.shutdownNow();
        if (message != null) message.dismiss();
    }

    private UpdatePolicy.Release readRelease(int operation) throws Exception {
        HttpURLConnection connection = open(URI.create(UpdatePolicy.MANIFEST_URL), "update.json", operation);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            long started = SystemClock.elapsedRealtime();
            int count;
            while ((count = input.read(buffer)) != -1) {
                ensureActive(operation);
                if (bytes.size() + count > UpdatePolicy.MAX_MANIFEST_BYTES) throw new UpdateFailure("The release manifest was too large.");
                if (SystemClock.elapsedRealtime() - started > 30_000) throw new UpdateFailure("Checking for updates timed out.");
                bytes.write(buffer, 0, count);
            }
            return parseRelease(new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)));
        } finally {
            connection.disconnect();
            activeConnection = null;
        }
    }

    private static UpdatePolicy.Release parseRelease(JSONObject json) throws Exception {
        if (json.length() != 7) throw new UpdateFailure("The release manifest format is not supported.");
        return new UpdatePolicy.Release((int) integer(json, "schemaVersion", 1, 1),
                integer(json, "versionCode", 1, Integer.MAX_VALUE), string(json, "versionName"),
                string(json, "apkUrl"), string(json, "sha256"), integer(json, "size", 1, UpdatePolicy.MAX_APK_BYTES),
                (int) integer(json, "minSdk", 26, 100));
    }

    private void persistAvailability() {
        SharedPreferences.Editor editor = preferences.edit();
        UpdatePolicy.Release release = availability.available();
        if (release == null) editor.remove("availableRelease");
        else {
            try {
                JSONObject json = new JSONObject().put("schemaVersion", 1).put("versionCode", release.versionCode)
                        .put("versionName", release.versionName).put("apkUrl", release.apkUri.toString())
                        .put("sha256", release.sha256).put("size", release.size).put("minSdk", release.minSdk);
                editor.putString("availableRelease", json.toString());
            } catch (org.json.JSONException ignored) { editor.remove("availableRelease"); }
        }
        editor.apply();
    }

    private void notifyAvailability() {
        UpdatePolicy.Release release = availability.available();
        availabilityListener.changed(release == null ? null : release.versionName);
    }

    private HttpURLConnection open(URI initial, String artifact, int operation) throws Exception {
        URI current = initial;
        for (int redirects = 0; redirects <= 4; redirects++) {
            ensureActive(operation);
            if (!UpdatePolicy.allowedDownloadUri(current, artifact)) throw new UpdateFailure("GitHub redirected outside the supported release download service.");
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            activeConnection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "GridLens-Android/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setUseCaches(false);
            int status;
            try { status = connection.getResponseCode(); }
            catch (Exception error) { connection.disconnect(); throw error; }
            if (status == 200) return connection;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.length() > 8192) throw new UpdateFailure("GitHub returned an invalid download redirect.");
                current = current.resolve(location);
                continue;
            }
            connection.disconnect();
            if (status == 404 && "update.json".equals(artifact)) throw new UpdateFailure("No Android release is available from GitHub yet.");
            if (status == 403 || status == 429) throw new UpdateFailure("GitHub is not allowing this update request right now. Try again later.");
            throw new UpdateFailure("GitHub could not provide the update (HTTP " + status + "). Try again later.");
        }
        throw new UpdateFailure("The update download redirected too many times.");
    }

    // Package visibility also lets on-device instrumentation exercise real Android APK parsing.
    void verifyArchive(File apk, UpdatePolicy.Release release) throws Exception {
        PackageManager manager = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || candidate.applicationInfo == null) throw new UpdateFailure("Android could not verify the downloaded APK.");
        PackageInfo installed = installedPackage();
        UpdatePolicy.verifyPackage(release, installed.packageName, versionCode(installed), signers(installed),
                candidate.packageName, versionCode(candidate), candidate.versionName,
                candidate.applicationInfo.minSdkVersion, signers(candidate), Build.VERSION.SDK_INT);
    }

    private PackageInfo installedPackage() throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(),
                Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static byte[][] signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signatures == null) return null;
        byte[][] result = new byte[signatures.length][];
        for (int i = 0; i < signatures.length; i++) result[i] = signatures[i].toByteArray();
        return result;
    }

    private static long integer(JSONObject json, String key, long min, long max) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new UpdateFailure("The release manifest contains an invalid number.");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new UpdateFailure("The release manifest contains an invalid number.");
        return number;
    }

    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new UpdateFailure("The release manifest contains an invalid value.");
        return (String) value;
    }

    private int begin(String label, boolean determinate) {
        busy = true;
        int operation = generation.incrementAndGet();
        progress = new ProgressDialog(activity);
        progress.setTitle("GridLens update");
        progress.setMessage(label);
        progress.setIndeterminate(!determinate);
        if (determinate) { progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL); progress.setMax(100); }
        progress.setCancelable(true);
        progress.setCanceledOnTouchOutside(false);
        progress.setOnCancelListener(dialog -> cancel());
        progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancel", (dialog, which) -> cancel());
        progress.show();
        return operation;
    }

    private void cancel() {
        generation.incrementAndGet();
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        if (task != null) task.cancel(true);
        finishProgress();
    }

    private void ensureActive(int operation) throws IOException {
        if (destroyed || generation.get() != operation || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    private void post(int operation, Runnable action) {
        activity.runOnUiThread(() -> {
            if (!destroyed && !activity.isFinishing() && generation.get() == operation) action.run();
        });
    }

    private void finishProgress() {
        if (progress != null) { progress.dismiss(); progress = null; }
        busy = false;
        silentCheck = false;
    }

    private void fail(int operation, Exception error) {
        post(operation, () -> {
            finishProgress();
            String detail = error instanceof UpdateFailure || error instanceof UpdatePolicy.ValidationFailure
                    ? error.getMessage() : "The update could not be downloaded or verified. Check your internet connection and available storage, then try again.";
            showMessage("Update not installed", detail);
        });
    }

    private void showMessage(String title, String detail) {
        if (destroyed || activity.isFinishing()) return;
        message = new AlertDialog.Builder(activity).setTitle(title).setMessage(detail).setPositiveButton("OK", null).show();
    }

    private File apkFile() { return new File(activity.getCacheDir(), "updates/update.apk"); }
    private Uri updateUri() { return Uri.parse("content://" + activity.getPackageName() + ".updates/update.apk"); }

    private static final class UpdateFailure extends Exception {
        UpdateFailure(String message) { super(message); }
    }
}
