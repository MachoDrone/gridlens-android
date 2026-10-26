package com.gridlens.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.function.LongSupplier;

/** Bounded allowlisted transport; independent of Activity, UI callbacks and the Android installer. */
final class UpdateDownloadEngine {
    static final long MAX_TRANSFER_MS = 10 * 60_000L;
    interface Response extends AutoCloseable {
        int status() throws Exception;
        String location();
        long contentLength();
        InputStream body() throws Exception;
        void close();
    }
    interface Transport { Response open(URI uri) throws Exception; }
    interface Check { void active() throws IOException; }
    interface Progress { void update(long downloaded) throws Exception; }
    static final class Failure extends IOException { Failure(String message) { super(message); } }

    static void transfer(UpdatePolicy.Release release, File partial, Transport transport, Check cancelled, Progress progress, LongSupplier elapsed) throws Exception {
        long started = elapsed.getAsLong();
        Check check = () -> { cancelled.active(); if (elapsed.getAsLong() - started > MAX_TRANSFER_MS) throw new Failure("The update download timed out."); };
        URI current = release.apkUri;
        for (int redirects = 0; redirects <= 4; redirects++) {
            check.active();
            if (!UpdatePolicy.allowedDownloadUri(current, "gridlens-android.apk")) throw new Failure("The download redirected outside the supported GitHub release service.");
            try (Response response = transport.open(current)) {
                int status = response.status();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.location();
                    if (location == null || location.length() > 8192) throw new Failure("The update returned an invalid redirect.");
                    current = current.resolve(location); continue;
                }
                if (status != 200) throw new Failure("The update download failed (HTTP " + status + "). Retry when the connection is available.");
                long advertised = response.contentLength();
                if (advertised >= 0 && advertised != release.size) throw new Failure("The download size does not match its release manifest.");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long total = 0;
                try (InputStream input = response.body(); FileOutputStream output = new FileOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        check.active(); total += count;
                        if (total > release.size || total > UpdatePolicy.MAX_APK_BYTES) throw new Failure("The download exceeded its expected size.");
                        digest.update(buffer, 0, count); output.write(buffer, 0, count); progress.update(total);
                    }
                    check.active(); output.getFD().sync();
                }
                UpdatePolicy.verifyDownloaded(release, total, digest.digest());
                return;
            }
        }
        throw new Failure("The update redirected too many times.");
    }
    private UpdateDownloadEngine() {}
}
