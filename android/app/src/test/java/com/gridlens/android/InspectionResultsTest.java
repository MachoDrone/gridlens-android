package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.List;

public class InspectionResultsTest {
    @Test public void elevatedNamespaceAddsWithoutDiscardingRootlessPublicIdentities() {
        SshDiscovery.Result initial = new SshDiscovery.Result(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, List.of("rootless-fixture", "shared-fixture"), 1);
        SshDiscovery.Result elevated = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of("root-fixture", "shared-fixture"), 2);
        SshDiscovery.Result merged = InspectionResults.merge(initial, elevated);
        assertEquals(List.of("rootless-fixture", "shared-fixture", "root-fixture"), merged.addresses);
        assertEquals(3, merged.unresolved);
        assertEquals(SshDiscovery.Status.SUCCESS, merged.status);
    }
    @Test public void deniedSudoPreservesVerifiedPartialDiscoveries() {
        SshDiscovery.Result initial = new SshDiscovery.Result(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, List.of("verified-fixture"), 1);
        SshDiscovery.Result denied = new SshDiscovery.Result(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, List.of(), 0);
        assertEquals(initial.addresses, InspectionResults.merge(initial, denied).addresses);
        assertEquals(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, InspectionResults.merge(initial, denied).status);
    }
}
