package com.gridlens.android;

/** Foreground notification policy. A manifest check never downloads or installs an APK. */
public final class UpdateAvailability {
    public static final long CHECK_INTERVAL_MS = 6 * 60 * 60_000L;
    private long lastAttemptAtMs;
    private UpdatePolicy.Release available;

    public UpdateAvailability(long lastAttemptAtMs, UpdatePolicy.Release retained,
                              long installedVersion, int deviceSdk) {
        this.lastAttemptAtMs = Math.max(0, lastAttemptAtMs);
        accept(retained, installedVersion, deviceSdk);
    }

    /** Also bounds retries after failures and process recreation; no timer or background work. */
    public boolean shouldCheck(long nowMs) {
        if (nowMs <= 0) return false;
        if (lastAttemptAtMs > nowMs) {
            // A backward wall-clock change must not trigger repeated automatic requests.
            lastAttemptAtMs = nowMs;
            return false;
        }
        return lastAttemptAtMs == 0 || nowMs - lastAttemptAtMs >= CHECK_INTERVAL_MS;
    }

    public void attempted(long nowMs) { lastAttemptAtMs = Math.max(0, nowMs); }
    public long lastAttemptAtMs() { return lastAttemptAtMs; }

    /** Call only after a complete validated manifest response. Errors retain prior evidence. */
    public void accept(UpdatePolicy.Release release, long installedVersion, int deviceSdk) {
        available = release != null && release.isNewerThan(installedVersion)
                && release.minSdk <= deviceSdk ? release : null;
    }

    public UpdatePolicy.Release available() { return available; }
}
