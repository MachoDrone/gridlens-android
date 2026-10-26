package com.gridlens.android;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class UpdateDownloadOwnershipTest {
    @Test public void aCancelledOrDestroyedOldWorkerCannotClearOrPublishOverANewServiceJob() throws Exception {
        UpdateDownloadOwnership owner = new UpdateDownloadOwnership();
        owner.activate("old"); assertTrue(owner.isCurrent("old"));
        owner.activate("new");
        int[] oldNotificationWrites = {0};
        assertFalse(owner.runIfCurrent("old", () -> oldNotificationWrites[0]++));
        assertFalse(owner.release("old", () -> oldNotificationWrites[0]++));
        assertFalse(owner.release("old")); assertFalse(owner.claim("old"));
        assertEquals(0, oldNotificationWrites[0]); assertTrue(owner.isCurrent("new"));
        assertTrue(owner.release("new", () -> {})); assertTrue(owner.vacant());
    }
    @Test public void newOwnershipCannotInterleaveInsideAnOlderGuardedNotificationOrFinish() throws Exception {
        UpdateDownloadOwnership owner = new UpdateDownloadOwnership(); owner.activate("old");
        CountDownLatch oldAction = new CountDownLatch(1), release = new CountDownLatch(1), newAttempt = new CountDownLatch(1), newActivated = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> old = workers.submit(() -> {
                try { owner.release("old", () -> { oldAction.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Fixture wait expired"); }); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            assertTrue(oldAction.await(5, TimeUnit.SECONDS));
            Future<?> next = workers.submit(() -> { newAttempt.countDown(); owner.activate("new"); newActivated.countDown(); });
            assertTrue(newAttempt.await(5, TimeUnit.SECONDS));
            assertFalse(newActivated.await(100, TimeUnit.MILLISECONDS));
            release.countDown(); old.get(5, TimeUnit.SECONDS); next.get(5, TimeUnit.SECONDS);
            assertTrue(owner.isCurrent("new"));
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test public void queuedOldCancellationCannotStopAnAlreadyReservedReplacementStart() throws Exception {
        UpdateDownloadOwnership owner = new UpdateDownloadOwnership();
        owner.activate("old-service-job");
        owner.activate("replacement-start-pending");
        int[] stoppedServices = {0};
        assertFalse(owner.retireIfCurrentOrVacant("old-service-job", () -> stoppedServices[0]++));
        assertEquals(0, stoppedServices[0]);
        assertTrue(owner.claim("replacement-start-pending"));
        assertTrue(owner.isCurrent("replacement-start-pending"));
    }
    @Test public void failedReplacementStartAllowsOldServiceCleanupWithoutAffectingALaterOwner() throws Exception {
        UpdateDownloadOwnership owner = new UpdateDownloadOwnership();
        owner.activate("old"); owner.activate("replacement-that-fails"); owner.release("replacement-that-fails");
        int[] cleanup = {0};
        assertTrue(owner.retireIfCurrentOrVacant("old", () -> cleanup[0]++));
        assertEquals(1, cleanup[0]); assertTrue(owner.vacant());
        owner.activate("later");
        assertFalse(owner.retireIfCurrentOrVacant("old", () -> cleanup[0]++));
        assertEquals(1, cleanup[0]); assertTrue(owner.isCurrent("later"));
    }
}
