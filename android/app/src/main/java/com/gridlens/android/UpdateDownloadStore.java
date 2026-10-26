package com.gridlens.android;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** One app-private, explicitly requested transfer. Activity destruction never changes its state. */
final class UpdateDownloadStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final File cache;
    private final Context context;
    private final boolean fixtureNamespace;
    static final class Record {
        final UpdatePolicy.Release release;
        final UpdateDownloadState state;
        final long startedAtMs, updatedAtMs;
        Record(UpdatePolicy.Release release, UpdateDownloadState state, long startedAtMs, long updatedAtMs) {
            this.release = release; this.state = state; this.startedAtMs = startedAtMs; this.updatedAtMs = updatedAtMs;
        }
    }
    UpdateDownloadStore(Context context) { this(context, null); }
    UpdateDownloadStore(Context context, String namespace) {
        if (namespace != null && (!BuildConfig.DEBUG || !namespace.matches("[a-z0-9-]{1,48}"))) throw new SecurityException("Debug fixture namespace required");
        this.context = context.getApplicationContext();
        fixtureNamespace = namespace != null;
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), namespace == null ? "update-download.json" : namespace + "-update-download.json"));
        cache = new File(context.getCacheDir(), namespace == null ? "updates" : "updates/" + namespace);
    }
    File partial(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid update file identity");
        return new File(cache, "transfer-" + id + ".part");
    }
    boolean hasPartials() { File[] files = cache.listFiles((folder, name) -> name.startsWith("transfer") && name.endsWith(".part")); return files != null && files.length > 0; }
    File apk() { return new File(cache, "update.apk"); }
    Record read() throws Exception { synchronized (LOCK) { return readLocked(); } }
    private Record readLocked() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return null;
        JSONObject json;
        try (InputStream input = file.openRead()) { json = new JSONObject(new String(NodeRuntime.readBounded(input, 32 * 1024), StandardCharsets.UTF_8)); }
        if (json.length() != 9 || UpdateReleaseJson.integer(json, "schema", 1, 1) != 1) throw new IllegalArgumentException("Invalid saved update");
        String id = UpdateReleaseJson.string(json, "id");
        if (!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw new IllegalArgumentException("Invalid saved update identity");
        UpdatePolicy.Release release = UpdateReleaseJson.parse(json.getJSONObject("release"));
        long started = UpdateReleaseJson.integer(json, "startedAtMs", 1, 8_640_000_000_000_000L);
        long updated = UpdateReleaseJson.integer(json, "updatedAtMs", 1, 8_640_000_000_000_000L);
        if (UpdateReleaseJson.integer(json, "totalBytes", 1, UpdatePolicy.MAX_APK_BYTES) != release.size) throw new IllegalArgumentException("Invalid saved update size");
        UpdateDownloadState state = new UpdateDownloadState(id, UpdateReleaseJson.string(json, "status"), release.versionName,
                UpdateReleaseJson.integer(json, "downloadedBytes", 0, release.size), release.size, UpdateReleaseJson.string(json, "detail"));
        if ("idle".equals(state.status) || state.ready() && state.downloadedBytes != release.size) throw new IllegalArgumentException("Invalid saved transfer status");
        return new Record(release, state, started, updated);
    }
    Record begin(UpdatePolicy.Release release) throws Exception {
        synchronized (LOCK) {
            Record prior = readLocked();
            if (prior != null && prior.state.active()) {
                if (prior.release.versionCode == release.versionCode && prior.release.sha256.equals(release.sha256)) return prior;
                throw new IllegalStateException("Cancel the current update download before starting another.");
            }
            if (!cache.isDirectory() && !cache.mkdirs()) throw new java.io.IOException("Update storage unavailable");
            if (prior != null && partial(prior.state.id).exists() && !partial(prior.state.id).delete()) throw new java.io.IOException("The previous partial update could not be cleared.");
            Record next = new Record(release, new UpdateDownloadState(UUID.randomUUID().toString(), "queued", release.versionName, 0, release.size,
                    "Preparing the update download. You can switch apps."), System.currentTimeMillis(), System.currentTimeMillis());
            writeLocked(next); return next;
        }
    }
    boolean update(String id, String status, long downloaded, String detail) throws Exception {
        if ("ready".equals(status)) throw new IllegalArgumentException("Verified file promotion is required before readiness");
        synchronized (LOCK) {
            Record current = readLocked();
            if (current == null || !current.state.id.equals(id) || !current.state.active()) return false;
            writeLocked(new Record(current.release, new UpdateDownloadState(id, status, current.release.versionName,
                    Math.min(downloaded, current.release.size), current.release.size, detail), current.startedAtMs, System.currentTimeMillis()));
            return true;
        }
    }
    /** File replacement and READY publication have the same current-job guard as metadata writes. */
    boolean promoteVerified(String id) throws Exception {
        synchronized (LOCK) {
            Record current = readLocked();
            if (current == null || !id.equals(current.state.id) || !"verifying".equals(current.state.status)) return false;
            File partial = partial(id);
            if (!partial.isFile() || partial.length() != current.release.size) throw new java.io.IOException("The verified update file is incomplete.");
            if (!fixtureNamespace) context.revokeUriPermission(android.net.Uri.parse("content://" + context.getPackageName() + ".updates/update.apk"), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (apk().exists() && !apk().delete()) throw new java.io.IOException("The previous update could not be replaced.");
            if (!partial.renameTo(apk())) throw new java.io.IOException("The verified update could not be saved.");
            writeLocked(new Record(current.release, new UpdateDownloadState(id, "ready", current.release.versionName, current.release.size,
                    current.release.size, "Verified update ready. Open GridLens and choose Install."), current.startedAtMs, System.currentTimeMillis()));
            return true;
        }
    }
    void invalidateMissingReadyFile() throws Exception {
        synchronized (LOCK) {
            Record current = readLocked();
            if (current != null && current.state.ready() && (!apk().isFile() || apk().length() != current.release.size))
                writeLocked(new Record(current.release, new UpdateDownloadState(current.state.id, "failed", current.release.versionName,
                        current.state.downloadedBytes, current.release.size, "The completed update file is no longer available. Tap Update to download it again."),
                        current.startedAtMs, System.currentTimeMillis()));
        }
    }
    void clearCompleted(long installedVersion) throws Exception {
        synchronized (LOCK) {
            Record current = readLocked();
            if (current != null && !current.state.active() && current.release.versionCode <= installedVersion) file.delete();
        }
    }
    void clearFixture(String id) throws Exception {
        if (!BuildConfig.DEBUG) throw new SecurityException("Debug instrumentation required");
        synchronized (LOCK) {
            Record current = readLocked();
            if (current == null || !id.equals(current.state.id) || current.state.active() || !"0.0.999".equals(current.release.versionName))
                throw new IllegalStateException("Matching inactive fixture download required");
            file.delete();
            if (partial(id).exists() && !partial(id).delete()) throw new java.io.IOException("Fixture cleanup failed");
            if (apk().exists() && !apk().delete()) throw new java.io.IOException("Fixture cleanup failed");
        }
    }
    private void writeLocked(Record value) throws Exception {
        JSONObject json = new JSONObject().put("schema", 1).put("id", value.state.id).put("release", UpdateReleaseJson.encode(value.release))
                .put("status", value.state.status).put("downloadedBytes", value.state.downloadedBytes).put("totalBytes", value.state.totalBytes)
                .put("detail", value.state.detail).put("startedAtMs", value.startedAtMs).put("updatedAtMs", value.updatedAtMs);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8); if (bytes.length > 32 * 1024) throw new IllegalArgumentException("Saved update exceeded bounds");
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); file.finishWrite(output); }
        catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
    }
}
