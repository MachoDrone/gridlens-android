package com.gridlens.android;

/** Foreground notification policy. A manifest check never downloads or installs an APK. */
public final class UpdateAvailability {
    public static final long CHECK_INTERVAL_MS = 5 * 60_000L;
    private static final long MAX_TIME_MS = 8_640_000_000_000_000L;
    private long lastAttemptAtMs;
    private long lastSuccessfulCheckAtMs;
    private long automaticRetryAtMs;
    private long installedVersion;
    private int deviceSdk;
    private String status;
    private UpdatePolicy.Release latest;
    private UpdatePolicy.Release available;

    /** Installed identity comes from the APK; latest identity comes only from a validated manifest. */
    public static final class State {
        public final String installedVersionName, latestVersionName, availableVersionName, status;
        public final long installedVersionCode, latestVersionCode, lastSuccessfulCheckAtMs, lastAttemptAtMs;
        private State(String installedVersionName, UpdateAvailability value) {
            this.installedVersionName = installedVersionName;
            installedVersionCode = value.installedVersion;
            latestVersionName = value.latest == null ? null : value.latest.versionName;
            latestVersionCode = value.latest == null ? 0 : value.latest.versionCode;
            availableVersionName = value.available == null ? null : value.available.versionName;
            status = value.status;
            lastSuccessfulCheckAtMs = value.lastSuccessfulCheckAtMs;
            lastAttemptAtMs = value.lastAttemptAtMs;
        }
    }

    public UpdateAvailability(long lastAttemptAtMs, UpdatePolicy.Release retained,
                              long installedVersion, int deviceSdk) {
        this(lastAttemptAtMs, 0, retained, "not-checked", installedVersion, deviceSdk);
    }

    public UpdateAvailability(long lastAttemptAtMs, long lastSuccessfulCheckAtMs,
                              UpdatePolicy.Release retained, String savedStatus,
                              long installedVersion, int deviceSdk) {
        this.lastAttemptAtMs = validTime(lastAttemptAtMs);
        this.lastSuccessfulCheckAtMs = retained == null ? 0 : validTime(lastSuccessfulCheckAtMs);
        this.installedVersion = installedVersion;
        this.deviceSdk = deviceSdk;
        latest = retained;
        reconcile();
        // A process that ended during a check has no completed result for that attempt.
        if ("failed".equals(savedStatus) || "checking".equals(savedStatus)) status = "failed";
    }

    /** Also bounds automatic retries after failures and process recreation. */
    public boolean shouldCheck(long nowMs) {
        if (validTime(nowMs) == 0) return false;
        if (lastAttemptAtMs > nowMs) {
            // A backward wall-clock change must not trigger repeated automatic requests.
            lastAttemptAtMs = nowMs;
            return false;
        }
        return nowMs >= automaticRetryAtMs && (lastAttemptAtMs == 0 || nowMs - lastAttemptAtMs >= CHECK_INTERVAL_MS);
    }

    /** Foreground timer delay; a clock rollback rebases the attempt without making a request. */
    public long nextCheckDelayMs(long nowMs) {
        if (shouldCheck(nowMs)) return 0;
        if (validTime(nowMs) == 0) return CHECK_INTERVAL_MS;
        long interval = lastAttemptAtMs == 0 ? 0 : Math.max(0, CHECK_INTERVAL_MS - (nowMs - lastAttemptAtMs));
        return Math.max(interval, Math.max(0, automaticRetryAtMs - nowMs));
    }

    public void attempted(long nowMs) { lastAttemptAtMs = validTime(nowMs); status = "checking"; }
    public long lastAttemptAtMs() { return lastAttemptAtMs; }
    public long lastSuccessfulCheckAtMs() { return lastSuccessfulCheckAtMs; }
    public String status() { return status; }
    public long automaticRetryAtMs() { return automaticRetryAtMs; }
    public void deferAutomaticUntil(long atMs) { automaticRetryAtMs = Math.max(automaticRetryAtMs, validTime(atMs)); }
    public boolean canCheckManually(long nowMs) { return validTime(nowMs) > 0 && nowMs >= automaticRetryAtMs; }
    public State snapshot(String installedVersionName) { return new State(installedVersionName, this); }

    /** Call only after a complete validated manifest response. Errors retain prior evidence. */
    public void accept(UpdatePolicy.Release release, long installedVersion, int deviceSdk) {
        accept(release, installedVersion, deviceSdk, lastAttemptAtMs);
    }
    public void accept(UpdatePolicy.Release release, long installedVersion, int deviceSdk, long checkedAtMs) {
        latest = release;
        this.installedVersion = installedVersion;
        this.deviceSdk = deviceSdk;
        lastSuccessfulCheckAtMs = release == null ? 0 : validTime(checkedAtMs);
        if (release != null) automaticRetryAtMs = 0;
        reconcile();
    }

    public void failed() { status = "failed"; }

    private void reconcile() {
        available = latest != null && latest.isNewerThan(installedVersion) && latest.minSdk <= deviceSdk ? latest : null;
        status = latest == null ? "not-checked" : available != null ? "available"
                : latest.isNewerThan(installedVersion) ? "incompatible"
                : lastSuccessfulCheckAtMs > 0 ? "up-to-date" : "not-checked";
    }
    private static long validTime(long value) { return value > 0 && value <= MAX_TIME_MS ? value : 0; }

    /** Retry-After is an automatic-request eligibility bound, never a recovery prediction. */
    public static long retryDeadline(String value, long nowMs) {
        if (value == null || value.length() > 128 || validTime(nowMs) == 0) return 0;
        String text = value.trim();
        try {
            if (text.matches("[0-9]+")) {
                java.math.BigInteger at = java.math.BigInteger.valueOf(nowMs).add(new java.math.BigInteger(text).multiply(java.math.BigInteger.valueOf(1000)));
                return at.min(java.math.BigInteger.valueOf(MAX_TIME_MS)).longValue();
            }
            long at = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return at > nowMs ? validTime(at) : 0;
        } catch (RuntimeException invalid) { return 0; }
    }

    public UpdatePolicy.Release available() { return available; }
    public UpdatePolicy.Release latest() { return latest; }
}
