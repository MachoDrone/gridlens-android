package com.gridlens.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A user-started transfer owns its foreground lifetime; Activities only observe it. */
public final class UpdateDownloadService extends Service {
    public static final String SHOW_DOWNLOAD = "com.gridlens.android.SHOW_UPDATE_DOWNLOAD";
    private static final String START = "com.gridlens.android.START_UPDATE_DOWNLOAD";
    private static final String CANCEL = "com.gridlens.android.CANCEL_UPDATE_DOWNLOAD";
    private static final String CHANNEL = "gridlens-update-downloads";
    private static final int NOTIFICATION = 4174;
    private static final UpdateDownloadOwnership ownership = new UpdateDownloadOwnership();
    private static volatile UpdateDownloadEngine.Transport fixtureTransport;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UpdateDownloadStore store;
    private UpdatePersistence persistence;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private Future<?> task;
    private volatile HttpURLConnection connection;
    private volatile boolean stopped;
    private volatile String currentId;

    static UpdateDownloadState start(Context context, UpdatePolicy.Release release) throws Exception {
        if (UpdatePreferences.open(context).cooldownDeadline() > System.currentTimeMillis()) throw new IOException("GitHub requested a wait before another download. Check Update for the retry-eligible time.");
        UpdateDownloadStore store = new UpdateDownloadStore(context);
        UpdateDownloadStore.Record record = store.begin(release);
        if (isRunning(record.state.id)) return record.state;
        ownership.activate(record.state.id);
        try { context.startForegroundService(new Intent(context, UpdateDownloadService.class).setAction(START).putExtra("downloadId", record.state.id)); }
        catch (RuntimeException error) {
            ownership.release(record.state.id);
            store.update(record.state.id, "failed", record.state.downloadedBytes, "Android did not allow the download to start. Open GridLens and retry.");
            throw new IOException("Android did not allow the download to start. Open GridLens and retry.");
        }
        return record.state;
    }
    static boolean isRunning(String id) { return ownership.isCurrent(id); }
    static void setTransportForTesting(Context context, URI expectedUri, UpdateDownloadEngine.Transport transport) throws Exception {
        UpdateDownloadStore store = new UpdateDownloadStore(context);
        if (!BuildConfig.DEBUG || !ownership.vacant() || transport == null || store.read() != null || store.apk().exists() || store.hasPartials()
                || !UpdatePolicy.allowedDownloadUri(expectedUri, "gridlens-android.apk") || !"github.com".equals(expectedUri.getHost())
                || !expectedUri.getPath().endsWith("/v0.0.999/gridlens-android.apk")) throw new SecurityException("Isolated debug update fixture required");
        if (!context.getSharedPreferences("update-download-test", MODE_PRIVATE).edit().putString("uri", expectedUri.toString()).commit())
            throw new IOException("Fixture state could not be saved");
        fixtureTransport = transport;
    }
    static void clearTransportForTesting(Context context, String id) throws Exception {
        if (!BuildConfig.DEBUG || !ownership.vacant()) throw new SecurityException("Stopped debug fixture required");
        new UpdateDownloadStore(context).clearFixture(id);
        if (!context.getSharedPreferences("update-download-test", MODE_PRIVATE).edit().clear().commit()) throw new IOException("Fixture state could not be cleared");
        fixtureTransport = null;
    }
    static void cancelExplicitly(Context context, String id) throws Exception {
        UpdateDownloadStore store = new UpdateDownloadStore(context);
        UpdateDownloadStore.Record record = store.read();
        if (record == null || !id.equals(record.state.id) || !record.state.active()) return;
        store.update(id, "cancelled", record.state.downloadedBytes, "Download cancelled. Tap Update to choose another download.");
        if (isRunning(id)) context.startService(new Intent(context, UpdateDownloadService.class).setAction(CANCEL).putExtra("downloadId", id));
    }
    @Override public void onCreate() {
        super.onCreate();
        store = new UpdateDownloadStore(this); persistence = UpdatePreferences.open(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "GridLens update downloads", NotificationManager.IMPORTANCE_LOW));
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GridLens:UpdateDownload");
        wakeLock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra("downloadId");
        if (intent != null && CANCEL.equals(intent.getAction())) {
            if (id != null && id.equals(currentId)) {
                try {
                    if (!ownership.retireIfCurrentOrVacant(id, () -> {
                        try { UpdateDownloadStore.Record record = store.read();
                            if (record != null && id.equals(record.state.id)) store.update(id, "cancelled", record.state.downloadedBytes,
                                    "Download cancelled. Tap Update to choose another download.");
                        } catch (Exception ignored) { }
                        stopTransfer(); stopForeground(STOP_FOREGROUND_REMOVE);
                        if (wakeLock.isHeld()) wakeLock.release();
                        stopSelfResult(startId);
                    })) stopTransfer(); // The new START is pending; stop only the old worker, never its successor's service.
                } catch (Exception ignored) { stopTransfer(); }
            }
            else if (currentId == null && ownership.vacant()) stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            UpdateDownloadStore.Record record = store.read();
            if (intent == null || !START.equals(intent.getAction()) || record == null || !record.state.id.equals(id) || !record.state.active()) {
                if (currentId == null) { if (id != null) ownership.release(id); stopSelf(startId); }
                return START_NOT_STICKY;
            }
            if (!ownership.claim(id)) { if (currentId == null) stopSelf(startId); return START_NOT_STICKY; }
            Notification notice = notification(record.state);
            if (!ownership.runIfCurrent(id, () -> {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(NOTIFICATION, notice);
            })) return START_NOT_STICKY;
            if (id.equals(currentId) && task != null && !task.isDone()) return START_NOT_STICKY;
            if (task != null && !task.isDone()) stopTransfer();
            currentId = id; stopped = false;
            wakeLock.acquire(UpdateDownloadEngine.MAX_TRANSFER_MS + 120_000);
            handler.postDelayed(() -> {
                if (!id.equals(currentId) || !ownership.isCurrent(id)) return;
                markFailure(id, "The update transfer or verification exceeded its time limit. Tap Update to retry.");
                stopTransfer(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            }, UpdateDownloadEngine.MAX_TRANSFER_MS + 120_000);
            task = worker.submit(() -> transfer(record, startId));
        } catch (Exception error) {
            markFailure(id, "Android could not maintain the download service. Open GridLens and retry.");
            if (id != null) ownership.release(id);
            stopTransfer(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId);
        }
        return START_NOT_STICKY;
    }
    private void transfer(UpdateDownloadStore.Record record, int startId) {
        String id = record.state.id;
        final long[] transferred = {0};
        try {
            active(id);
            if (!store.partial(id).getParentFile().isDirectory() && !store.partial(id).getParentFile().mkdirs()) throw new IOException("Update storage is unavailable.");
            store.update(id, "downloading", 0, "Downloading the signed update. You can switch apps.");
            final long[] lastProgress = {0};
            UpdateDownloadEngine.transfer(record.release, store.partial(id), this::open,
                    () -> active(id), downloaded -> {
                        transferred[0] = downloaded;
                        if (SystemClock.elapsedRealtime() - lastProgress[0] < 1000 && downloaded != record.release.size) return;
                        lastProgress[0] = SystemClock.elapsedRealtime();
                        if (!store.update(id, "downloading", downloaded, "Downloading the signed update. You can switch apps.")) throw new IOException("Download no longer active");
                        notifyCurrent(id);
                    }, SystemClock::elapsedRealtime);
            active(id);
            if (!store.update(id, "verifying", record.release.size, "Download complete. Verifying its package and signing identity…")) throw new IOException("Download no longer active");
            notifyCurrent(id);
            UpdateArchiveVerifier.verify(this, store.partial(id), record.release);
            active(id);
            if (!store.promoteVerified(id)) throw new IOException("The verified transfer was cancelled or replaced.");
        } catch (Exception error) {
            try {
                UpdateDownloadStore.Record current = store.read();
                if (current != null && id.equals(current.state.id) && current.state.active())
                    store.update(id, "failed", transferred[0], error instanceof UpdatePolicy.ValidationFailure || error instanceof UpdateDownloadEngine.Failure || error instanceof UpdateArchiveVerifier.Failure ? error.getMessage()
                            : "The transfer or verification could not finish. Check your connection and storage, then tap Update to retry.");
            } catch (Exception ignored) { }
            store.partial(id).delete();
        } finally { handler.post(() -> finish(id, startId)); }
    }
    private UpdateDownloadEngine.Response open(URI uri) throws Exception {
        if (persistence.cooldownDeadline() > System.currentTimeMillis()) throw new IOException("GitHub requested a delay before another download request.");
        if (BuildConfig.DEBUG) {
            String fixtureUri = getSharedPreferences("update-download-test", MODE_PRIVATE).getString("uri", null);
            if (fixtureUri != null) {
                UpdateDownloadEngine.Transport transport = fixtureTransport;
                if (!fixtureUri.equals(uri.toString()) || transport == null) throw new IOException("Synthetic update transport is unavailable; network access is disabled for this fixture.");
                return transport.open(uri);
            }
        }
        HttpURLConnection opened = (HttpURLConnection) uri.toURL().openConnection(); connection = opened;
        opened.setInstanceFollowRedirects(false); opened.setConnectTimeout(15_000); opened.setReadTimeout(30_000);
        opened.setRequestProperty("User-Agent", "GridLens-Android/" + BuildConfig.VERSION_NAME); opened.setRequestProperty("Accept-Encoding", "identity");
        opened.setUseCaches(false);
        return new UpdateDownloadEngine.Response() {
            public int status() throws Exception {
                if (stopped || Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
                int status = opened.getResponseCode();
                if (status == 403 || status == 429 || status == 503) {
                    long retry = UpdateAvailability.retryDeadline(opened.getHeaderField("Retry-After"), System.currentTimeMillis());
                    if (retry > 0) persistence.retainCooldown(retry);
                }
                return status;
            }
            public String location() { return opened.getHeaderField("Location"); }
            public long contentLength() { return opened.getContentLengthLong(); }
            public InputStream body() throws Exception { return opened.getInputStream(); }
            public void close() { opened.disconnect(); if (connection == opened) connection = null; }
        };
    }
    private void active(String id) throws IOException {
        if (stopped || !id.equals(currentId) || !ownership.isCurrent(id) || Thread.currentThread().isInterrupted()) throw new IOException("Download interrupted");
    }
    private void notifyCurrent(String id) {
        try { ownership.runIfCurrent(id, () -> {
            UpdateDownloadStore.Record record = store.read();
            if (record != null && id.equals(record.state.id)) notifications.notify(NOTIFICATION, notification(record.state));
        }); } catch (Exception ignored) { }
    }
    private void finish(String id, int startId) {
        if (!id.equals(currentId)) return;
        try { ownership.retireIfCurrentOrVacant(id, () -> {
            try {
                UpdateDownloadStore.Record record = store.read();
                if (record != null && id.equals(record.state.id) && record.state.active()) {
                    store.update(id, "interrupted", record.state.downloadedBytes, "The transfer ended before completion. Tap Update to retry.");
                    record = store.read();
                }
                if (record != null && id.equals(record.state.id) && !"cancelled".equals(record.state.status)) {
                    stopForeground(STOP_FOREGROUND_DETACH); notifications.notify(NOTIFICATION, notification(record.state));
                } else stopForeground(STOP_FOREGROUND_REMOVE);
            } catch (Exception ignored) { stopForeground(STOP_FOREGROUND_REMOVE); }
            if (wakeLock.isHeld()) wakeLock.release();
            currentId = null; stopSelfResult(startId);
        }); } catch (Exception ignored) { }
    }
    private Notification notification(UpdateDownloadState state) {
        Intent open = new Intent(this, MainActivity.class).setAction(SHOW_DOWNLOAD).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, 4174, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(state.ready() ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_sys_download)
                .setContentTitle("GridLens " + state.versionName + " update").setContentText(state.progressText()).setSubText(state.detail)
                .setStyle(new Notification.BigTextStyle().bigText(state.progressText() + "\n" + state.detail)).setContentIntent(tap)
                .setOnlyAlertOnce(true).setOngoing(state.active()).setAutoCancel(!state.active());
        if (state.active()) {
            builder.setProgress(100, state.percent, "queued".equals(state.status));
            Intent cancel = new Intent(this, UpdateDownloadService.class).setAction(CANCEL)
                    .setData(Uri.parse("gridlens-update-cancel:" + state.id)).putExtra("downloadId", state.id);
            builder.addAction(new Notification.Action.Builder(null, "Cancel download", PendingIntent.getService(this, 4175, cancel,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        }
        return builder.build();
    }
    private void markFailure(String id, String detail) {
        try { UpdateDownloadStore.Record record = store.read(); if (record != null && record.state.id.equals(id)) store.update(id, "failed", record.state.downloadedBytes, detail); }
        catch (Exception ignored) { }
    }
    private void stopTransfer() {
        stopped = true;
        HttpURLConnection active = connection; if (active != null) active.disconnect();
        if (task != null) task.cancel(true);
    }
    @Override public void onTimeout(int startId, int foregroundServiceType) {
        markFailure(currentId, "Android stopped the background transfer after its service time limit. Tap Update to retry.");
        stopTransfer(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopTransfer();
        try { UpdateDownloadStore.Record record = store.read(); if (record != null && record.state.id.equals(currentId) && record.state.active())
            store.update(currentId, "interrupted", record.state.downloadedBytes, "The transfer was interrupted before completion. Tap Update to retry."); }
        catch (Exception ignored) { }
        if (currentId != null) ownership.release(currentId);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        worker.shutdownNow(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
