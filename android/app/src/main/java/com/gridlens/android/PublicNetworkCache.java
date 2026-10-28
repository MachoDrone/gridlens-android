package com.gridlens.android;

import java.util.concurrent.ConcurrentHashMap;

/** Shared success cache with shorter bounded retries for an unavailable public address. */
final class PublicNetworkCache {
    static final long CACHE_MS = 30 * 60 * 1000L;
    static final long FAILURE_RETRY_MS = 60_000L;
    interface Store { String read(String pcId); boolean write(String pcId, String value); }
    static final class Aborted extends Exception { Aborted() { super("PC network read cancelled"); } }
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
            long retryAt = Math.max(cached == null ? 0 : cached.retryAtMs, Math.max(observedRetryAt.getOrDefault(pcId, 0L), savedDeadline(store.read(pcId + ":retryAtMs"))));
            // A clock regression must neither renew a reading nor bypass its request allowance.
            if (cached != null && cached.observedAtMs > now) return null;
            long lastAttempt = savedDeadline(store.read(pcId + ":attemptAtMs"));
            if (retryAt > now || lastAttempt > now || lastAttempt > 0 && now - lastAttempt < FAILURE_RETRY_MS) return cached;
            long interval = cached != null && cached.publicIp != null ? CACHE_MS : FAILURE_RETRY_MS;
            if (cached != null && now - cached.observedAtMs < interval) return cached;
            PublicIpReader.Observation attempt = PublicIpReader.unavailable(now);
            persist(pcId + ":attemptAtMs", Long.toString(now));
            PublicIpReader.Observation result;
            result = reader.read();
            if (result == null) throw new Aborted();
            if (result.retryAtMs > 0) preserveRetry(pcId, Math.min(PublicIpReader.MAX_SAFE_INTEGER,
                    result.retryAtMs + Math.max(0, attempt.observedAtMs - result.observedAtMs)));
            // The pre-request attempt remains the retry barrier if wall time moves backward.
            if (result.observedAtMs < attempt.observedAtMs) return cached;
            persist(pcId, result.json());
            return result;
        }
    }
    void preserveRetry(String pcId, long deadline) {
        deadline = Math.max(deadline, Math.max(observedRetryAt.getOrDefault(pcId, 0L), savedDeadline(store.read(pcId + ":retryAtMs"))));
        if (deadline > 0) {
            observedRetryAt.merge(pcId, deadline, Math::max);
            persist(pcId + ":retryAtMs", Long.toString(deadline));
        }
    }
    PublicIpReader.Observation peek(String pcId) { return PublicIpReader.parse(store.read(pcId)); }
    long nextReadAt(String pcId) {
        PublicIpReader.Observation cached = peek(pcId);
        long retry = Math.max(cached == null ? 0 : cached.retryAtMs, Math.max(observedRetryAt.getOrDefault(pcId, 0L), savedDeadline(store.read(pcId + ":retryAtMs"))));
        long attempted = savedDeadline(store.read(pcId + ":attemptAtMs"));
        if (attempted > 0) retry = Math.max(retry, attempted + FAILURE_RETRY_MS);
        if (cached == null) return retry;
        return Math.max(retry, cached.observedAtMs + (cached.publicIp == null ? FAILURE_RETRY_MS : CACHE_MS));
    }
    boolean pendingDelivery(String fleetId, String pcId) {
        PublicIpReader.Observation cached = peek(pcId);
        return cached != null && !cached.json().equals(store.read(deliveryKey(fleetId, pcId)));
    }
    long nextDeliveryAt(String fleetId, String pcId) { return savedDeadline(store.read(deliveryKey(fleetId, pcId) + ":retryAtMs")); }
    void deliveryAttempt(String fleetId, String pcId) { persist(deliveryKey(fleetId, pcId) + ":retryAtMs", Long.toString(clock.now() + FAILURE_RETRY_MS)); }
    void delivered(String fleetId, String pcId, PublicIpReader.Observation observation) {
        persist(deliveryKey(fleetId, pcId), observation.json());
    }
    private static String deliveryKey(String fleetId, String pcId) {
        if (fleetId == null || !fleetId.matches("[a-f0-9-]{36}") || pcId == null || !pcId.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Verified fleet PC required");
        return pcId + ":delivered:" + fleetId;
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
