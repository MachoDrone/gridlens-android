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

    @Test public void firstForegroundCheckAndFailuresShareFiveMinuteLimitAcrossRecreation() {
        UpdateAvailability state = new UpdateAvailability(0, null, 3, 35);
        assertEquals(300_000, UpdateAvailability.CHECK_INTERVAL_MS);
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

    @Test public void statesDistinguishUnknownCheckingSuccessfulFailureAndCompatibility() {
        UpdateAvailability state = new UpdateAvailability(0, null, 3, 35);
        assertEquals("not-checked", state.snapshot("0.2.0").status);
        assertNull(state.snapshot("0.2.0").latestVersionName);
        assertEquals(0, state.snapshot("0.2.0").lastSuccessfulCheckAtMs);
        state.attempted(NOW);
        assertEquals("checking", state.snapshot("0.2.0").status);
        state.failed();
        assertEquals("failed", state.snapshot("0.2.0").status);
        assertNull(state.snapshot("0.2.0").latestVersionName);
        assertEquals(0, state.lastSuccessfulCheckAtMs());
        state.attempted(NOW + 300_000);
        state.accept(release(3, 26), 3, 35, NOW + 301_000);
        UpdateAvailability.State success = state.snapshot("0.2.0");
        assertEquals("up-to-date", success.status);
        assertEquals("0.2.0", success.installedVersionName);
        assertEquals(3, success.installedVersionCode);
        assertEquals("0.3.0", success.latestVersionName);
        assertEquals(3, success.latestVersionCode);
        assertEquals(NOW + 300_000, success.lastAttemptAtMs);
        assertEquals(NOW + 301_000, success.lastSuccessfulCheckAtMs);
        assertNull(success.availableVersionName);
        state.attempted(NOW + 600_000);
        state.accept(release(4, 36), 3, 35, NOW + 601_000);
        assertEquals("incompatible", state.snapshot("0.2.0").status);
        assertEquals(4, state.snapshot("0.2.0").latestVersionCode);
        assertNull(state.snapshot("0.2.0").availableVersionName);
    }

    @Test public void failedCheckRetainsNewerVersionAndOriginalSuccessTimeAcrossReopening() {
        UpdateAvailability state = new UpdateAvailability(0, null, 3, 35);
        state.attempted(NOW);
        state.accept(release(4, 26), 3, 35, NOW + 1000);
        assertEquals("available", state.status());
        state.attempted(NOW + 300_000);
        assertEquals("checking cannot erase known availability", "0.3.0", state.snapshot("0.2.0").availableVersionName);
        state.failed();
        UpdateAvailability restored = new UpdateAvailability(state.lastAttemptAtMs(), state.lastSuccessfulCheckAtMs(),
                state.latest(), state.status(), 3, 35);
        UpdateAvailability.State after = restored.snapshot("0.2.0");
        assertEquals("failed", after.status);
        assertEquals("0.3.0", after.availableVersionName);
        assertEquals("0.3.0", after.latestVersionName);
        assertEquals(NOW + 1000, after.lastSuccessfulCheckAtMs);
        assertEquals(NOW + 300_000, after.lastAttemptAtMs);
        assertFalse(restored.shouldCheck(NOW + 300_001));
    }

    @Test public void processDeathDuringCheckingDoesNotRestoreAnUpToDateClaim() {
        UpdateAvailability state = new UpdateAvailability(NOW, NOW - 1000, release(3, 26), "checking", 3, 35);
        assertEquals("failed", state.snapshot("0.2.0").status);
        assertEquals(NOW - 1000, state.lastSuccessfulCheckAtMs());
        assertNull(state.available());
        assertEquals(300_000, state.nextCheckDelayMs(NOW));
    }

    @Test public void aValidatedCurrentVersionIsStillShownAfterItsNextCheckFails() {
        UpdateAvailability state = new UpdateAvailability(NOW, NOW, release(3, 26), "up-to-date", 3, 35);
        state.attempted(NOW + 300_000); state.failed();
        assertEquals("failed", state.status());
        assertEquals(3, state.snapshot("0.2.0").latestVersionCode);
        assertEquals(NOW, state.lastSuccessfulCheckAtMs());
        assertNull(state.available());
    }

    @Test public void automaticTimerUsesRemainingIntervalAndManualChecksResetItsDeadline() {
        UpdateAvailability state = new UpdateAvailability(0, null, 3, 35);
        assertEquals(0, state.nextCheckDelayMs(NOW));
        state.attempted(NOW);
        assertEquals(300_000, state.nextCheckDelayMs(NOW));
        assertEquals(1000, state.nextCheckDelayMs(NOW + 299_000));
        assertEquals(0, state.nextCheckDelayMs(NOW + 300_000));
        // Manual checks may start early, then automatic work respects that new attempt.
        state.attempted(NOW + 60_000);
        assertEquals(240_000, state.nextCheckDelayMs(NOW + 120_000));
        state.failed();
        assertEquals(240_000, state.nextCheckDelayMs(NOW + 120_000));
    }

    @Test public void malformedTimesAndMissingCachedManifestCannotInventSuccessfulEvidence() {
        UpdateAvailability state = new UpdateAvailability(Long.MAX_VALUE, Long.MAX_VALUE, null, "up-to-date", 3, 35);
        assertEquals(0, state.lastAttemptAtMs());
        assertEquals(0, state.lastSuccessfulCheckAtMs());
        assertEquals("not-checked", state.status());
        assertTrue(state.shouldCheck(NOW));
        UpdateAvailability legacy = new UpdateAvailability(NOW, release(3, 26), 3, 35);
        assertEquals("legacy cache has no successful check date", "not-checked", legacy.status());
        assertEquals(300_000, state.nextCheckDelayMs(-1));
        assertEquals(300_000, state.nextCheckDelayMs(Long.MAX_VALUE));
    }

    @Test public void explicitProviderDelaySurvivesFailureAndReopeningAndManualChecksAlsoWait() {
        UpdateAvailability state = new UpdateAvailability(NOW, null, 3, 35);
        state.deferAutomaticUntil(NOW + 3_600_000); state.failed();
        assertFalse(state.shouldCheck(NOW + 300_000));
        assertEquals(3_300_000, state.nextCheckDelayMs(NOW + 300_000));
        UpdateAvailability restored = new UpdateAvailability(state.lastAttemptAtMs(), state.lastSuccessfulCheckAtMs(), state.latest(), state.status(), 3, 35);
        restored.deferAutomaticUntil(state.automaticRetryAtMs());
        restored.deferAutomaticUntil(NOW + 60_000);
        assertEquals(NOW + 3_600_000, restored.automaticRetryAtMs());
        assertFalse(restored.shouldCheck(NOW + 3_599_999));
        assertFalse(restored.canCheckManually(NOW + 120_000));
        assertFalse(restored.canCheckManually(NOW + 3_599_999));
        assertTrue(restored.shouldCheck(NOW + 3_600_000));
        assertTrue(restored.canCheckManually(NOW + 3_600_000));
        restored.attempted(NOW + 3_600_000);
        restored.accept(release(4, 26), 3, 35, NOW + 3_601_000);
        assertEquals(0, restored.automaticRetryAtMs());
        assertEquals(299_000, restored.nextCheckDelayMs(NOW + 3_601_000));
        assertTrue("manual action bypasses only the local cadence", restored.canCheckManually(NOW + 3_601_001));
    }

    @Test public void retryAfterAcceptsBoundedDelayOrHttpDateWithoutOverflowOrInventingARecoveryTime() {
        assertEquals(NOW + 120_000, UpdateAvailability.retryDeadline("120", NOW));
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.Instant.ofEpochMilli(NOW + 60_000).atZone(java.time.ZoneOffset.UTC));
        assertEquals(NOW + 60_000, UpdateAvailability.retryDeadline(date, NOW));
        assertEquals(8_640_000_000_000_000L, UpdateAvailability.retryDeadline("999999999999999999999999999999999", NOW));
        for (String invalid : new String[]{"", "-1", "later", "1.5", "Wed, 01 Jan 2020 00:00:00 GMT", "1".repeat(129)})
            assertEquals(invalid, 0, UpdateAvailability.retryDeadline(invalid, NOW));
        assertEquals(0, UpdateAvailability.retryDeadline(null, NOW));
    }
}
