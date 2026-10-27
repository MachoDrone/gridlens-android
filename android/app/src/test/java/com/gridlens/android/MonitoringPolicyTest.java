package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MonitoringPolicyTest {
    @Test public void requiresForegroundApprovalAndUnpausedTarget() {
        assertTrue(MonitoringPolicy.due(true, true, false, 1000, 0, 0, 30000));
        assertFalse(MonitoringPolicy.due(false, true, false, 1000, 0, 0, 30000));
        assertFalse(MonitoringPolicy.due(true, false, false, 1000, 0, 0, 30000));
        assertFalse(MonitoringPolicy.due(true, true, true, 1000, 0, 0, 30000));
    }
    @Test public void reopeningDoesNotResetGpuRewardsOrProviderDeadlines() {
        long attempted = 1_700_000_000_000L;
        assertFalse(MonitoringPolicy.due(true, true, false, attempted + 29999, attempted, 0, MonitoringPolicy.GPU_INTERVAL_MS));
        assertTrue(MonitoringPolicy.due(true, true, false, attempted + 30000, attempted, 0, MonitoringPolicy.GPU_INTERVAL_MS));
        assertFalse(MonitoringPolicy.due(true, true, false, attempted + 299999, attempted, 0, MonitoringPolicy.REWARDS_INTERVAL_MS));
        assertTrue(MonitoringPolicy.due(true, true, false, attempted + 300000, attempted, 0, MonitoringPolicy.REWARDS_INTERVAL_MS));
        long retry = MonitoringPolicy.retryAt(attempted, 86400);
        assertFalse(MonitoringPolicy.due(true, true, false, retry - 1, attempted, retry, MonitoringPolicy.REWARDS_INTERVAL_MS));
        assertTrue(MonitoringPolicy.due(true, true, false, retry, attempted, retry, MonitoringPolicy.REWARDS_INTERVAL_MS));
        assertFalse(MonitoringPolicy.due(true, true, false, attempted - 1000, attempted, 0, MonitoringPolicy.GPU_INTERVAL_MS));
    }
    @Test public void authenticationAndIdentityFailuresNeedExplicitActionWithoutPasswordRetries() {
        assertTrue(MonitoringPolicy.pausesTarget("credentials-required"));
        assertTrue(MonitoringPolicy.pausesTarget("identity-changed"));
        assertTrue(MonitoringPolicy.pausesTarget("binding-changed"));
        assertTrue(MonitoringPolicy.pausesTarget("authorization-required"));
        assertFalse(MonitoringPolicy.pausesTarget("unreachable"));
        assertEquals(Long.MAX_VALUE, MonitoringPolicy.retryAt(1000, Long.MAX_VALUE));
        assertEquals(300, MonitoringPolicy.providerDelaySeconds(true, 0));
        assertEquals(86400, MonitoringPolicy.providerDelaySeconds(true, 86400));
        assertEquals(0, MonitoringPolicy.providerDelaySeconds(false, 0));
        assertTrue(MonitoringPolicy.suspendsCredentialProfile("credentials-required"));
        assertFalse("A node rewards denial must not block other PCs sharing SSH credentials",
                MonitoringPolicy.suspendsCredentialProfile("authorization-required"));
        assertFalse(MonitoringPolicy.suspendsCredentialProfile("unreachable"));
        assertFalse(MonitoringPolicy.suspendsCredentialProfile("identity-changed"));
        assertFalse("GPU recorder permissions must not disable PC/source monitoring", MonitoringPolicy.pausesTarget("gpu-access-required"));
        assertFalse("GPU recorder permissions are not rejected SSH credentials", MonitoringPolicy.suspendsCredentialProfile("gpu-access-required"));
    }
    @Test public void twoHundredPcFleetCannotBeStarvedByEarlyTargetsBecomingDueAgain() {
        java.util.Map<String, Long> attempts = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 200; i++) attempts.put("pc-" + i, 0L);
        java.util.Set<String> visited = new java.util.HashSet<>();
        long now = 1_700_000_000_000L;
        for (int batch = 0; batch < 50; batch++) {
            java.util.Map<String, Long> due = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Long> target : attempts.entrySet())
                if (MonitoringPolicy.due(true, true, false, now, target.getValue(), 0, MonitoringPolicy.GPU_INTERVAL_MS)) due.put(target.getKey(), target.getValue());
            java.util.List<String> chosen = MonitoringPolicy.oldestFirst(due, MonitoringPolicy.MAX_CONCURRENT_PCS);
            assertEquals(4, chosen.size());
            for (String id : chosen) { assertTrue("Every never-attempted PC gets its turn first", visited.add(id)); attempts.put(id, now); }
            now += 2000; // Early PCs become due well before the final group is reached.
        }
        assertEquals(200, visited.size());
    }
}
