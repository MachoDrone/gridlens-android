package com.gridlens.android;

import org.junit.Test;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public final class DiscoveryScannerTest {
    @Test public void probesAllAddressesAndPortsWithExactly32MaximumWorkers() throws Exception {
        List<String> addresses = DiscoveryPolicy.expandRanges(Collections.singletonList("192.0.2.0/26"));
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger(), finalProgress = new AtomicInteger();
        CountDownLatch firstWave = new CountDownLatch(32);
        List<DiscoveryPolicy.Candidate> found = DiscoveryScanner.scan(addresses, Arrays.asList(22,2222), candidate -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max); calls.incrementAndGet(); firstWave.countDown();
            try { assertTrue(firstWave.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            active.decrementAndGet(); return candidate.port == 2222;
        }, () -> false, (done,total,count) -> finalProgress.accumulateAndGet(done, Math::max));
        assertEquals(32, peak.get()); assertEquals(124, calls.get()); assertEquals(124, finalProgress.get()); assertEquals(62, found.size());
    }
    @Test public void cancellationStopsFurtherWorkAndDoesNotReturnPartialFleet() {
        AtomicBoolean cancelled = new AtomicBoolean(true); AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class, () -> DiscoveryScanner.scan(Collections.singletonList("192.0.2.1"), Collections.singletonList(22), candidate -> { calls.incrementAndGet(); return true; }, cancelled::get, null));
        assertEquals(0, calls.get());
    }
    @Test public void candidateCapacityFailsExplicitlyInsteadOfTruncating() {
        List<String> addresses = DiscoveryPolicy.expandRanges(Collections.singletonList("10.45.0.0/20"));
        IOException failure = assertThrows(IOException.class, () -> DiscoveryScanner.scan(addresses, Collections.singletonList(22), candidate -> true, () -> false, null));
        assertTrue(failure.getMessage().contains("Too many"));
    }
}
