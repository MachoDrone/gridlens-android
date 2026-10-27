package com.gridlens.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class MonitoringRecoveryTest {
    @Test public void onlyPreviouslyApprovedCredentialPausesAreEligible() {
        assertTrue(MonitoringRecovery.eligible(true, true, "credentials-required", false));
        assertFalse(MonitoringRecovery.eligible(false, true, "credentials-required", false));
        assertFalse(MonitoringRecovery.eligible(true, false, "credentials-required", false));
        assertFalse(MonitoringRecovery.eligible(true, true, "credentials-required", true));
        for (String status : List.of("paused", "identity-changed", "binding-changed", "authorization-required", "active", "unreachable"))
            assertFalse(status, MonitoringRecovery.eligible(true, true, status, false));
    }
    @Test public void aRejectedSharedProfileCannotTriggerTwoHundredPasswordRetries() throws Exception {
        List<String> targets = new ArrayList<>(); for (int i = 0; i < 200; i++) targets.add("pc-" + i);
        AtomicInteger attempts = new AtomicInteger();
        List<MonitoringRecovery.Result<String>> results = MonitoringRecovery.check(targets, target -> "shared", target -> {
            attempts.incrementAndGet(); return "credentials-required";
        }, () -> false, result -> {}, () -> {});
        assertEquals(200, results.size());
        assertEquals("No later PC may receive the rejected shared credential", 1, attempts.get());
        assertTrue(MonitoringRecovery.verified(results, target -> "shared").isEmpty());
    }
    @Test public void laterSharedProfileRejectionExcludesEarlierSuccessButNotIndependentProfiles() throws Exception {
        CountDownLatch successfulSibling = new CountDownLatch(1);
        List<MonitoringRecovery.Result<String>> results = MonitoringRecovery.check(List.of("shared:verified", "shared:rejected", "independent:verified"),
                target -> target.split(":")[0], target -> {
                    if (target.endsWith("rejected")) { assertTrue(successfulSibling.await(3, TimeUnit.SECONDS)); return "credentials-required"; }
                    if (target.startsWith("shared:")) successfulSibling.countDown();
                    return "active";
                }, () -> false, result -> {}, () -> {});
        assertEquals(List.of("independent:verified"), MonitoringRecovery.verified(results, target -> target.split(":")[0]));
    }
    @Test public void cancellationBeforeDispatchMakesNoConnectionAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<MonitoringRecovery.Result<String>> results = MonitoringRecovery.check(List.of("pc"), target -> "credential", target -> {
            attempts.incrementAndGet(); return "active";
        }, () -> true, result -> {}, () -> {});
        assertEquals(0, attempts.get()); assertTrue(results.isEmpty());
    }
    @Test public void failedPersistenceStopsTheBatchAndCancelsInFlightChecks() {
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> MonitoringRecovery.check(List.of("pc"), target -> "credential", target -> "credentials-required",
                () -> false, result -> { throw new IllegalStateException("Permission storage unavailable"); }, () -> cancelled.set(true)));
        assertTrue(cancelled.get());
    }
}
