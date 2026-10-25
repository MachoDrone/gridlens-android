package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdateAvailabilityTest {
    private static final long NOW = 1_700_000_000_000L;

    private UpdatePolicy.Release release(int version, int minSdk) {
        return new UpdatePolicy.Release(1, version, "0.3.0",
                "https://github.com/MachoDrone/gridlens-android-releases/releases/download/v0.3.0/gridlens-android.apk",
                "a".repeat(64), 123, minSdk);
    }

    @Test public void firstForegroundCheckAndFailuresShareSixHourLimitAcrossRecreation() {
        UpdateAvailability state = new UpdateAvailability(0, null, 3, 35);
        assertTrue(state.shouldCheck(NOW));
        state.attempted(NOW);
        assertFalse(state.shouldCheck(NOW));
        assertFalse(state.shouldCheck(NOW + UpdateAvailability.CHECK_INTERVAL_MS - 1));
        UpdateAvailability recreated = new UpdateAvailability(state.lastAttemptAtMs(), null, 3, 35);
        assertFalse(recreated.shouldCheck(NOW + 60_000));
        assertTrue(recreated.shouldCheck(NOW + UpdateAvailability.CHECK_INTERVAL_MS));
    }

    @Test public void newerCompatibleReleaseSurvivesFailedCheckButNotSuccessfulWithdrawal() {
        UpdatePolicy.Release release = release(4, 26);
        UpdateAvailability state = new UpdateAvailability(0, release, 3, 35);
        assertSame(release, state.available());
        state.attempted(NOW); // A failed check does not supply replacement evidence.
        assertSame(release, state.available());
        state.accept(release(3, 26), 3, 35);
        assertNull(state.available());
    }

    @Test public void installedDowngradedAndIncompatibleReleasesCannotHighlight() {
        for (int version : new int[]{1, 3}) {
            assertNull(new UpdateAvailability(NOW, release(version, 26), 3, 35).available());
        }
        assertNull(new UpdateAvailability(NOW, release(4, 36), 3, 35).available());
        assertNull(new UpdateAvailability(NOW, null, 3, 35).available());
        assertNotNull(new UpdateAvailability(NOW, release(4, 35), 3, 35).available());
        UpdateAvailability updated = new UpdateAvailability(NOW, release(4, 26), 4, 35);
        assertNull(updated.available());
    }

    @Test public void backwardClockChangeDefersInsteadOfCreatingARetryLoop() {
        UpdateAvailability state = new UpdateAvailability(NOW, null, 3, 35);
        long changed = NOW - 86_400_000;
        assertFalse(state.shouldCheck(changed));
        assertEquals(changed, state.lastAttemptAtMs());
        assertFalse(state.shouldCheck(changed + 60_000));
        assertTrue(state.shouldCheck(changed + UpdateAvailability.CHECK_INTERVAL_MS));
        assertFalse(state.shouldCheck(-1));
        assertFalse(state.shouldCheck(0));
    }
}
