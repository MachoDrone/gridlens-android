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
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); return PublicIpReader.unavailable(now.get()); };
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
        assertNull(cache.getOrRead(PC, reader)); assertEquals(0, reads.get());
        PublicNetworkCache recovered = new PublicNetworkCache(store, () -> 1000);
        store.writes = 0; store.failWrite = 2;
        assertThrows(IllegalStateException.class, () -> recovered.getOrRead(PC, reader)); assertEquals(1, reads.get());
        assertNull(recovered.getOrRead(PC, reader)); assertEquals(1, reads.get());
        assertNull(new PublicNetworkCache(store, () -> 1000).getOrRead(PC, reader)); assertEquals(1, reads.get());
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
    @Test public void failed025ObservationRecoversAtOneMinuteWithoutRetimingItsPrivateValue() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        String legacy = new PublicIpReader.Observation(1000, null, null, "192.168.1.20", 900L).json();
        store.values.put(PC, legacy);
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); return reading(now.get()); };
        now.set(60_999);
        assertEquals(legacy, cache.getOrRead(PC, reader).json()); assertEquals(0, reads.get());
        now.set(61_000);
        PublicIpReader.Observation recovered = new PublicNetworkCache(store, now::get).getOrRead(PC, reader);
        assertEquals("8.8.8.8", recovered.publicIp); assertEquals(1, reads.get());
        now.set(120_000); assertEquals(61_000, cache.getOrRead(PC, reader).observedAtMs); assertEquals(1, reads.get());
    }
    @Test public void resultClockRegressionCannotShortenThePersistedAttemptAllowance() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        PublicIpReader.Observation held = cache.getOrRead(PC, () -> { reads.incrementAndGet(); now.set(500); return reading(500); });
        assertNull(held);
        now.set(60_999); assertNull(cache.getOrRead(PC, () -> { reads.incrementAndGet(); return reading(now.get()); })); assertEquals(1, reads.get());
        now.set(61_000); assertEquals("8.8.8.8", cache.getOrRead(PC, () -> { reads.incrementAndGet(); return reading(now.get()); }).publicIp); assertEquals(2, reads.get());
    }
    @Test public void explicitProviderWaitPersistsBeyondTheOrdinaryFailureRetryAcrossRestart() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        cache.getOrRead(PC, () -> { reads.incrementAndGet(); return new PublicIpReader.Observation(1000, null, null, "10.0.0.1", 1000L, 3_601_000); });
        now.set(61_000);
        assertNull(new PublicNetworkCache(store, now::get).getOrRead(PC, () -> { reads.incrementAndGet(); return reading(now.get()); }).publicIp);
        assertEquals(1, reads.get());
        now.set(3_601_000);
        assertEquals("8.8.8.8", cache.getOrRead(PC, () -> { reads.incrementAndGet(); return reading(now.get()); }).publicIp); assertEquals(2, reads.get());
    }
    @Test public void storageExceptionBlocksThisCacheBeforeAnotherExternalRequest() throws Exception {
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(new PublicNetworkCache.Store() {
            public String read(String key) { return null; }
            public boolean write(String key, String value) { writes.incrementAndGet(); throw new IllegalStateException("Fixture unavailable storage"); }
        }, () -> 1000);
        PublicNetworkCache.Reader reader = () -> { reads.incrementAndGet(); return reading(1000); };
        assertThrows(IllegalStateException.class, () -> cache.getOrRead(PC, reader));
        assertNull(cache.getOrRead(PC, reader)); assertEquals(0, reads.get()); assertEquals(1, writes.get());
    }
    @Test public void cancelledReadPreservesCachedSourceAndDurableAttemptBarrier() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000);
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        PublicIpReader.Observation original = cache.getOrRead(PC, () -> reading(now.get()));
        now.addAndGet(PublicNetworkCache.CACHE_MS);
        assertThrows(PublicNetworkCache.Aborted.class, () -> cache.getOrRead(PC, () -> { throw new PublicNetworkCache.Aborted(); }));
        assertEquals(original.json(), cache.peek(PC).json());
        assertEquals(now.get() + PublicNetworkCache.FAILURE_RETRY_MS, new PublicNetworkCache(store, now::get).nextReadAt(PC));
        assertEquals(original.json(), cache.getOrRead(PC, () -> { fail("Cancelled attempt must retain retry allowance"); return null; }).json());
    }
    @Test public void deliveryRetryUsesExactCachedObservationWithoutAnotherReadOrTimeChange() throws Exception {
        Store store = new Store(); AtomicLong now = new AtomicLong(1000); AtomicInteger reads = new AtomicInteger();
        PublicNetworkCache cache = new PublicNetworkCache(store, now::get);
        String fleet = "11111111-1111-4111-8111-111111111111";
        PublicIpReader.Observation original = cache.getOrRead(PC, () -> { reads.incrementAndGet(); return reading(now.get()); });
        assertTrue(cache.pendingDelivery(fleet, PC)); cache.deliveryAttempt(fleet, PC);
        assertEquals(61000, cache.nextDeliveryAt(fleet, PC));
        now.set(61000); PublicNetworkCache restarted = new PublicNetworkCache(store, now::get);
        assertTrue(restarted.pendingDelivery(fleet, PC)); assertEquals(original.json(), restarted.peek(PC).json());
        restarted.delivered(fleet, PC, restarted.peek(PC)); assertFalse(restarted.pendingDelivery(fleet, PC));
        assertEquals(1, reads.get()); assertEquals(1000, restarted.peek(PC).publicObservedAtMs.longValue());
        assertTrue(restarted.pendingDelivery("22222222-2222-4222-8222-222222222222", PC));
    }

}
