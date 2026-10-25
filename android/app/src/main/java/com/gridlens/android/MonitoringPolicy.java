package com.gridlens.android;

/** Persisted per-PC attempt deadlines; opening a page never bypasses either allowance. */
final class MonitoringPolicy {
    static final long GPU_INTERVAL_MS = 30_000;
    static final long REWARDS_INTERVAL_MS = 300_000;
    static final int MAX_CONCURRENT_PCS = 4;
    static boolean due(boolean foreground, boolean approved, boolean paused,
                       long now, long lastAttempt, long retryAt, long interval) {
        if (!foreground || !approved || paused || now <= 0 || retryAt > now || lastAttempt > now) return false;
        return lastAttempt <= 0 || now - lastAttempt >= interval;
    }
    static long retryAt(long now, long seconds) {
        if (seconds <= 0) return now;
        // Preserve the provider's full delay; saturate instead of overflowing into an immediate retry.
        return seconds > (Long.MAX_VALUE - now) / 1000 ? Long.MAX_VALUE : now + seconds * 1000;
    }
    static long providerDelaySeconds(boolean rateLimited, long reportedSeconds) {
        return rateLimited ? Math.max(300, reportedSeconds) : reportedSeconds;
    }
    static boolean pausesTarget(String status) {
        return "credentials-required".equals(status) || "identity-changed".equals(status)
                || "binding-changed".equals(status) || "authorization-required".equals(status);
    }
    static boolean suspendsCredentialProfile(String status) {
        return "credentials-required".equals(status);
    }
    static java.util.List<String> oldestFirst(java.util.Map<String, Long> eligible, int slots) {
        java.util.List<String> result = new java.util.ArrayList<>(eligible.keySet());
        result.sort(java.util.Comparator.comparingLong(eligible::get));
        return new java.util.ArrayList<>(result.subList(0, Math.min(Math.max(slots, 0), result.size())));
    }
    private MonitoringPolicy() {}
}
