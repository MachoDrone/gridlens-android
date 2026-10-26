package com.gridlens.android;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public final class PublicNetworkCacheTest {
    private static final String PC = "a".repeat(64);
    private static final class Store implements PublicNetworkCache.Store {
        final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
        int writes; int failWrite;
        public String read(String key) { return values.get(key); }
        public synchronized boolean write(String key, String value) { if (++writes == failWrite) return false; values.put(key, value); return true; }
    }
    private static PublicIpReader.Observation reading(long at) { return new PublicIpReader.Observation(at, "8.8.8.8", at, "192.168.1.20", at); }
    @Test public void samePcAcrossHostsAndProcessRestartsKeepsOriginalTimesForThirtyMinutes() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); return reading(now.get()); };
        assertEquals(1000, cache.getOrRead(PC, reader).observedAtMs);
        now.set(2000);
        assertEquals(1000, cache.getOrRead(PC, reader).observedAtMs);
        assertEquals(1000, new PublicNetworkCache(store, now::get).getOrRead(PC, reader).observedAtMs);
        assertEquals(1, reads.get());
        now.set(1000 + PublicNetworkCache.CACHE_MS);
        assertEquals(now.get(), cache.getOrRead(PC, reader).observedAtMs); assertEquals(2, reads.get());
        cache.getOrRead("b".repeat(64), reader); assertEquals(3, reads.get());
    }
    @Test public void failuresAreCachedAndClockRollbackNeverTriggersAnotherLookup() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); throw new IllegalStateException("fixture remote failure"); };
        assertNull(cache.getOrRead(PC, reader).publicIp);
        assertNull(cache.getOrRead(PC, reader).publicIp); assertEquals(1, reads.get());
        now.set(999); assertNull(cache.getOrRead(PC, reader)); assertEquals(1, reads.get());
    }
    @Test public void durableAttemptPreventsARepeatAfterLostResultAndFailedAttemptWritesPreventReads() throws Exception {
        Store store = new Store(); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, () -> 1000);
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); return reading(1000); };
        store.failWrite = 1;
        assertThrows(IllegalStateException.class, () -> cache.getOrRead(PC, reader)); assertEquals(0, reads.get());
        store.writes = 0; store.failWrite = 2;
        assertThrows(IllegalStateException.class, () -> cache.getOrRead(PC, reader)); assertEquals(1, reads.get());
        assertNull(cache.getOrRead(PC, reader).publicIp); assertEquals(1, reads.get());
    }
    @Test public void concurrentBindingsShareOneInFlightPcRead() throws Exception {
        Store store = new Store(); PublicNetworkCache cache = new PublicNetworkCache(store, () -> 1000);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); return reading(1000); };
            Future<PublicIpReader.Observation> first = workers.submit(() -> cache.getOrRead(PC, reader));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<PublicIpReader.Observation> second = workers.submit(() -> cache.getOrRead(PC, reader));
            release.countDown();
            assertEquals(first.get(3, TimeUnit.SECONDS).json(), second.get(3, TimeUnit.SECONDS).json()); assertEquals(1, reads.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
}
