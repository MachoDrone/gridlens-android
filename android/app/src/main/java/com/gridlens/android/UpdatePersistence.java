package com.gridlens.android;

import java.io.IOException;

/** Separates ordinary check metadata from the monotonic provider request deadline. */
final class UpdatePersistence {
    static final String COOLDOWN_KEY = "automaticRetryAtMs";
    private static final long MAX_TIME_MS = 8_640_000_000_000_000L;
    private static final Object COOLDOWN_LOCK = new Object();
    interface Store {
        long readLong(String key);
        Editor edit();
    }
    interface Editor {
        Editor putLong(String key, long value);
        Editor putString(String key, String value);
        Editor remove(String key);
        void apply();
        boolean commit();
    }
    private final Store store;
    private long localCooldown;
    UpdatePersistence(Store store) { this.store = store; }

    /** Re-read shared storage at every dispatch boundary, including after Activity recreation. */
    long cooldownDeadline() {
        synchronized (COOLDOWN_LOCK) {
            long stored = store.readLong(COOLDOWN_KEY);
            if (stored > 0 && stored <= MAX_TIME_MS) localCooldown = Math.max(localCooldown, stored);
            return localCooldown;
        }
    }
    boolean providerAllowsRequest(long nowMs) { return nowMs >= cooldownDeadline(); }

    /** The only writer of COOLDOWN_KEY. A late controller cannot shorten another receipt. */
    void retainCooldown(long atMs) throws IOException {
        if (atMs <= 0 || atMs > MAX_TIME_MS) return;
        synchronized (COOLDOWN_LOCK) {
            localCooldown = Math.max(cooldownDeadline(), atMs);
            if (!store.edit().putLong(COOLDOWN_KEY, localCooldown).commit())
                throw new IOException("Provider retry deadline could not be saved");
        }
    }

    /** This editor deliberately has no authority over the independently received provider delay. */
    void saveMetadata(UpdateAvailability availability, String latestManifest) {
        Editor editor = store.edit().putLong("lastAttemptAtMs", availability.lastAttemptAtMs())
                .putLong("lastSuccessfulCheckAtMs", availability.lastSuccessfulCheckAtMs())
                .putString("lastCheckStatus", availability.status()).remove("availableRelease");
        if (latestManifest == null) editor.remove("latestRelease");
        else editor.putString("latestRelease", latestManifest);
        editor.apply();
    }
}
