package com.gridlens.android;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class UpdatePersistenceTest {
    private static final long NOW = 1_700_000_000_000L;

    private static final class MemoryStore implements UpdatePersistence.Store {
        final Map<String, Object> values = new HashMap<>();
        final List<Long> cooldownWrites = new ArrayList<>();
        volatile Runnable beforeMetadataApply = () -> {};
        volatile java.util.function.LongConsumer beforeCooldownCommit = value -> {};
        volatile boolean failCommit;
        @Override public synchronized long readLong(String key) {
            Object value = values.get(key); return value instanceof Long ? (Long) value : 0;
        }
        @Override public UpdatePersistence.Editor edit() {
            return new UpdatePersistence.Editor() {
                final Map<String, Object> changes = new HashMap<>();
                final Set<String> removed = new HashSet<>();
                public UpdatePersistence.Editor putLong(String key, long value) { changes.put(key, value); return this; }
                public UpdatePersistence.Editor putString(String key, String value) { changes.put(key, value); return this; }
                public UpdatePersistence.Editor remove(String key) { removed.add(key); return this; }
                private void write() {
                    synchronized (MemoryStore.this) {
                        for (String key : removed) values.remove(key);
                        values.putAll(changes);
                        if (changes.containsKey(UpdatePersistence.COOLDOWN_KEY)) cooldownWrites.add((Long) changes.get(UpdatePersistence.COOLDOWN_KEY));
                    }
                }
                public void apply() {
                    if (changes.containsKey("lastCheckStatus")) beforeMetadataApply.run();
                    write();
                }
                public boolean commit() {
                    if (failCommit) return false;
                    if (changes.containsKey(UpdatePersistence.COOLDOWN_KEY)) beforeCooldownCommit.accept((Long) changes.get(UpdatePersistence.COOLDOWN_KEY));
                    write(); return true;
                }
            };
        }
    }

    private UpdatePolicy.Release release() {
        return new UpdatePolicy.Release(1, 6, "0.2.3",
                "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.2.3/gridlens-android.apk",
                "a".repeat(64), 123, 26);
    }

    @Test public void staleMetadataEditorCannotEraseAProviderDeadlineCommittedWhileItWasOpen() throws Exception {
        MemoryStore store = new MemoryStore();
        UpdatePersistence metadata = new UpdatePersistence(store), olderController = new UpdatePersistence(store);
        UpdateAvailability status = new UpdateAvailability(0, null, 5, 35);
        status.attempted(NOW);
        CountDownLatch editorReady = new CountDownLatch(1), apply = new CountDownLatch(1);
        store.beforeMetadataApply = () -> {
            editorReady.countDown();
            try { if (!apply.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test editor was not released"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> pendingMetadata = worker.submit(() -> metadata.saveMetadata(status, null));
            assertTrue(editorReady.await(5, TimeUnit.SECONDS));
            olderController.retainCooldown(NOW + 3_600_000);
            apply.countDown(); pendingMetadata.get(5, TimeUnit.SECONDS);
            assertEquals(NOW + 3_600_000, store.readLong(UpdatePersistence.COOLDOWN_KEY));
            assertFalse(new UpdatePersistence(store).providerAllowsRequest(NOW + 300_000));
            assertEquals("Metadata edits must never write the cooldown key", 1, store.cooldownWrites.size());
        } finally { apply.countDown(); worker.shutdownNow(); }
    }

    @Test public void queuedManualDispatchRechecksLateHeadersFromAnOlderController() throws Exception {
        MemoryStore store = new MemoryStore();
        UpdatePersistence previous = new UpdatePersistence(store), current = new UpdatePersistence(store);
        assertEquals(0, current.cooldownDeadline());
        assertTrue(current.providerAllowsRequest(NOW)); // The tap occurred before the older response arrived.
        previous.retainCooldown(NOW + 600_000);
        assertFalse("Worker dispatch must re-read shared provider evidence", current.providerAllowsRequest(NOW + 1));
        assertFalse(new UpdatePersistence(store).providerAllowsRequest(NOW + 599_999));
        assertTrue(current.providerAllowsRequest(NOW + 600_000));
    }

    @Test public void aSuccessfulCurrentCheckCannotClearAFutureDelayLearnedByAnotherController() throws Exception {
        MemoryStore store = new MemoryStore();
        UpdatePersistence previous = new UpdatePersistence(store), current = new UpdatePersistence(store);
        UpdateAvailability status = new UpdateAvailability(0, null, 5, 35);
        status.attempted(NOW);
        assertEquals(0, current.cooldownDeadline());
        previous.retainCooldown(NOW + 3_600_000);
        status.accept(release(), 5, 35, NOW + 1000);
        current.saveMetadata(status, "validated-manifest-fixture");
        status.deferAutomaticUntil(current.cooldownDeadline());
        assertEquals("available", status.status());
        assertEquals("0.2.3", status.snapshot("0.2.2").availableVersionName);
        assertFalse(status.shouldCheck(NOW + 300_000));
        assertFalse(status.canCheckManually(NOW + 300_000));
        assertEquals(NOW + 3_600_000, store.readLong(UpdatePersistence.COOLDOWN_KEY));
        previous.retainCooldown(NOW + 30_000);
        assertEquals(NOW + 3_600_000, current.cooldownDeadline());
        assertTrue(current.providerAllowsRequest(NOW + 3_600_001));
        current.saveMetadata(status, "validated-manifest-fixture");
        assertEquals("Expired deadlines may remain stored without blocking", NOW + 3_600_000, store.readLong(UpdatePersistence.COOLDOWN_KEY));
    }

    @Test public void concurrentControllerReceiptsOnlyIncreaseTheDurableDeadline() throws Exception {
        MemoryStore store = new MemoryStore();
        UpdatePersistence first = new UpdatePersistence(store), second = new UpdatePersistence(store);
        CountDownLatch oldCommitReady = new CountDownLatch(1), releaseOldCommit = new CountDownLatch(1);
        CountDownLatch newerStarted = new CountDownLatch(1), newerCommitReady = new CountDownLatch(1);
        store.beforeCooldownCommit = deadline -> {
            if (deadline == NOW + 30_000) {
                oldCommitReady.countDown();
                try { if (!releaseOldCommit.await(5, TimeUnit.SECONDS)) throw new AssertionError("Old receipt was not released"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            } else newerCommitReady.countDown();
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> old = workers.submit(() -> { try { first.retainCooldown(NOW + 30_000); } catch (IOException error) { throw new AssertionError(error); } });
            assertTrue(oldCommitReady.await(5, TimeUnit.SECONDS));
            Future<?> newer = workers.submit(() -> {
                newerStarted.countDown();
                try { second.retainCooldown(NOW + 600_000); } catch (IOException error) { throw new AssertionError(error); }
            });
            assertTrue(newerStarted.await(5, TimeUnit.SECONDS));
            assertFalse("Controllers must serialize the read-max-write transaction", newerCommitReady.await(100, TimeUnit.MILLISECONDS));
            releaseOldCommit.countDown(); old.get(5, TimeUnit.SECONDS); newer.get(5, TimeUnit.SECONDS);
            assertEquals(NOW + 600_000, new UpdatePersistence(store).cooldownDeadline());
            long previous = 0;
            for (long recorded : store.cooldownWrites) { assertTrue(recorded >= previous); previous = recorded; }
        } finally { releaseOldCommit.countDown(); workers.shutdownNow(); }
    }

    @Test public void failedPersistenceStillBlocksTheCurrentProcessAndCanBeRetriedWithoutShortening() throws Exception {
        MemoryStore store = new MemoryStore(); UpdatePersistence persistence = new UpdatePersistence(store);
        store.failCommit = true;
        assertThrows(IOException.class, () -> persistence.retainCooldown(NOW + 600_000));
        assertFalse(persistence.providerAllowsRequest(NOW));
        store.failCommit = false;
        persistence.retainCooldown(NOW + 300_000);
        assertEquals(NOW + 600_000, new UpdatePersistence(store).cooldownDeadline());
    }
}
