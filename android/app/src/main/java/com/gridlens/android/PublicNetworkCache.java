package com.gridlens.android;

import java.util.concurrent.ConcurrentHashMap;

/** One source-side lookup per verified PC per 30 minutes, including failed reads. */
final class PublicNetworkCache {
    static final long CACHE_MS = 30 * 60 * 1000L;
    interface Store { String read(String pcId); boolean write(String pcId, String value); }
    interface Reader { PublicIpReader.Observation read() throws Exception; }
    private final Store store;
    private final PublicIpReader.Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    PublicNetworkCache(Store store, PublicIpReader.Clock clock) { this.store = store; this.clock = clock; }
    PublicIpReader.Observation getOrRead(String pcId, Reader reader) throws Exception {
        if (pcId == null || !pcId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Verified PC identity required");
        Object lock = locks.computeIfAbsent(pcId, ignored -> new Object());
        synchronized (lock) {
            long now = clock.now();
            PublicIpReader.Observation cached = PublicIpReader.parse(store.read(pcId));
            // A clock regression must neither renew a reading nor bypass its request allowance.
            if (cached != null && cached.observedAtMs > now) return null;
            if (cached != null && now - cached.observedAtMs < CACHE_MS) return cached;
            PublicIpReader.Observation attempt = PublicIpReader.unavailable(now);
            if (!store.write(pcId, attempt.json())) throw new IllegalStateException("Network lookup attempt could not be saved");
            PublicIpReader.Observation result;
            try { result = reader.read(); } catch (Exception unavailable) { result = PublicIpReader.unavailable(clock.now()); }
            if (result == null) result = attempt;
            if (!store.write(pcId, result.json())) throw new IllegalStateException("Network lookup result could not be saved");
            return result;
        }
    }
}
