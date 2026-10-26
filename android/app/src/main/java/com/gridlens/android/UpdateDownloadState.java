package com.gridlens.android;

import java.util.Locale;

/** Public display state only; it carries no authority to start a transfer or install an APK. */
public final class UpdateDownloadState {
    public final String id, status, versionName, detail;
    public final long downloadedBytes, totalBytes;
    public final int percent;
    public UpdateDownloadState(String id, String status, String versionName, long downloadedBytes, long totalBytes, String detail) {
        if (!java.util.Arrays.asList("idle", "queued", "downloading", "verifying", "ready", "failed", "cancelled", "interrupted").contains(status)
                || downloadedBytes < 0 || totalBytes < 0 || downloadedBytes > totalBytes || totalBytes > UpdatePolicy.MAX_APK_BYTES
                || "ready".equals(status) && (totalBytes == 0 || downloadedBytes != totalBytes)
                || detail == null || detail.length() > 400) throw new IllegalArgumentException("Invalid update transfer state");
        this.id = id; this.status = status; this.versionName = versionName; this.downloadedBytes = downloadedBytes; this.totalBytes = totalBytes; this.detail = detail;
        percent = totalBytes == 0 ? 0 : (int) (100 * downloadedBytes / totalBytes);
    }
    public boolean active() { return "queued".equals(status) || "downloading".equals(status) || "verifying".equals(status); }
    public boolean ready() { return "ready".equals(status); }
    public String progressText() { return totalBytes == 0 ? "Size unavailable" : String.format(Locale.ROOT, "%.2f / %.2f MB · %d%%", downloadedBytes / 1_000_000.0, totalBytes / 1_000_000.0, percent); }
    public static UpdateDownloadState idle() { return new UpdateDownloadState(null, "idle", null, 0, 0, ""); }
}
