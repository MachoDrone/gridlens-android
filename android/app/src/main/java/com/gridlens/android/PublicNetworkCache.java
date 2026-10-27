package com.gridlens.android;

import java.util.concurrent.ConcurrentHashMap;

/** Shared success cache with shorter bounded retries for an unavailable public address. */
final class PublicNetworkCache {
    static final long CACHE_MS = 30 * 60 * 1000L;
    static final long FAILURE_RETRY_MS = 60_000L;
    interface Store { String read(String pcId); boolean write(String pcId, String value); }
    interface Reader { PublicIpReader.Observation read() throws Exception; }
    private final Store store;
    private final PublicIpReader.Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> observedRetryAt = new ConcurrentHashMap<>();
    private volatile boolean storageBlocked;
    PublicNetworkCache(Store store, PublicIpReader.Clock clock) { this.store = store; this.clock = clock; }
    PublicIpReader.Observation getOrRead(String pcId, Reader reader) throws Exception {
        if (pcId == null || !pcId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Verified PC identity required");
        Object lock = locks.computeIfAbsent(pcId, ignored -> new Object());
        synchronized (lock) {
            if (storageBlocked) return null;
            long now = clock.now();
            PublicIpReader.Observation cached = PublicIpReader.parse(store.read(pcId));
            long retryAt = Math.max(observedRetryAt.getOrDefault(pcId, 0L), savedDeadline(store.read(pcId + ":retryAtMs")));
            // A clock regression must neither renew a reading nor bypass its request allowance.
            if (cached != null && cached.observedAtMs > now) return null;
            if (retryAt > now) return cached;
            long interval = cached != null && cached.publicIp != null ? CACHE_MS : FAILURE_RETRY_MS;
            if (cached != null && now - cached.observedAtMs < interval) return cached;
            PublicIpReader.Observation attempt = PublicIpReader.unavailable(now);
            persist(pcId, attempt.json());
            PublicIpReader.Observation result;
            try { result = reader.read(); } catch (Exception unavailable) { result = PublicIpReader.unavailable(clock.now()); }
            if (result == null) result = attempt;
            if (result.retryAtMs > 0) {
                long deadline = Math.min(PublicIpReader.MAX_SAFE_INTEGER, result.retryAtMs + Math.max(0, attempt.observedAtMs - result.observedAtMs));
                deadline = Math.max(retryAt, deadline);
                observedRetryAt.merge(pcId, deadline, Math::max);
                persist(pcId + ":retryAtMs", Long.toString(deadline));
            }
            // The pre-request attempt remains the retry barrier if wall time moves backward.
            if (result.observedAtMs < attempt.observedAtMs) result = attempt;
            persist(pcId, result.json());
            return result;
        }
    }
    private void persist(String key, String value) {
        try { if (store.write(key, value)) return; }
        catch (RuntimeException unavailable) { /* Do not let a storage exception bypass the attempt barrier. */ }
        storageBlocked = true;
        throw new IllegalStateException("Network lookup state could not be saved");
    }
    private static long savedDeadline(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,15}")) return 0;
        try { long at = Long.parseLong(value); return at <= PublicIpReader.MAX_SAFE_INTEGER ? at : 0; }
        catch (NumberFormatException invalid) { return 0; }
    }
}
