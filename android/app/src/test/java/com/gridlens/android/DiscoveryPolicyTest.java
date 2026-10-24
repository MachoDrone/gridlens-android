package com.gridlens.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class DiscoveryPolicyTest {
    @Test public void preservesFullSlash16AndDeduplicatesOverlappingRanges() {
        List<String> addresses = DiscoveryPolicy.expandRanges(Arrays.asList("10.47.98.1/16", "10.47.0.0/24"));
        assertEquals(65534, addresses.size());
        assertEquals("10.47.0.1", addresses.get(0));
        assertEquals("10.47.255.254", addresses.get(addresses.size() - 1));
        assertTrue(addresses.contains("10.47.255.1"));
    }
    @Test public void refusesBroadAndDefaultRoutesAndNeverSweepsIpv6() {
        for (String range : Arrays.asList("10.0.0.0/8", "0.0.0.0/0", "192.168.0.0/15", "fd42::/64", "example.test/24", "10.0.0.1/33", "127.0.0.1/32", "224.0.0.1/32")) {
            assertThrows(range, IllegalArgumentException.class, () -> DiscoveryPolicy.expandRanges(Collections.singletonList(range)));
        }
        assertEquals(1, DiscoveryPolicy.expandRanges(Collections.singletonList("fd42::123/128")).size());
        assertEquals(Arrays.asList("192.0.2.0", "192.0.2.1"), DiscoveryPolicy.expandRanges(Collections.singletonList("192.0.2.0/31")));
    }
    @Test public void validatesPortsAndCompletePublicAddresses() {
        assertEquals(Arrays.asList(22, 2222), DiscoveryPolicy.ports(Arrays.asList(22,2222,2222)));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryPolicy.ports(Collections.singletonList(0)));
        assertTrue(DiscoveryPolicy.publicAddress("1".repeat(32)));
        assertTrue(DiscoveryPolicy.publicAddress("1".repeat(31) + "2"));
        assertFalse(DiscoveryPolicy.publicAddress("z".repeat(44)));
        assertFalse(DiscoveryPolicy.publicAddress("1".repeat(33)));
        assertFalse(DiscoveryPolicy.publicAddress("0".repeat(32)));
    }
    @Test public void commandSurfaceHasNoUntrustedCommandOrKeyPaths() {
        String command = DiscoveryPolicy.inventoryCommand("docker", "a".repeat(64), true);
        assertEquals("/usr/bin/sudo -S -p '' -- /usr/bin/docker exec " + "a".repeat(64) + " /usr/bin/podman ps --no-trunc --format '{{.ID}}\\t{{.Image}}\\t{{.Names}}'", command);
        for (String bad : Arrays.asList("../../key", "abc; id", "a".repeat(63), "--privileged"))
            assertThrows(IllegalArgumentException.class, () -> DiscoveryPolicy.inventoryCommand("docker", bad, false));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryPolicy.inventoryCommand("bash", null, false));
        assertFalse(command.contains("inspect")); assertFalse(command.contains("nosana-node address"));
    }
    @Test public void onlyRunningVerifiedNosanaImagesAndPublicProxyNamesAreAccepted() {
        String address = "1".repeat(31) + "2";
        String rows = "a".repeat(64) + "\tdocker.io/nosana/nosana-node:latest\tnode-custom\n" + "b".repeat(64) + "\tregistry.hub.docker.com/nosana/frpc:0.1.0\tfrpc-api-" + address;
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        assertEquals(0, SshDiscovery.collect(DiscoveryPolicy.parseInventory(rows), addresses));
        assertEquals(Collections.singleton(address), addresses);
        assertEquals(1, SshDiscovery.collect(DiscoveryPolicy.parseInventory(rows.replace("nosana/frpc", "untrusted/frpc")), new LinkedHashSet<>()));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryPolicy.parseInventory(rows + "\n" + rows));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryPolicy.parseInventory("a\timage\tname"));
    }
    @Test public void multipleNodesRemainSeparateFromUnresolvedAndAmbiguousProxies() {
        String node = "a".repeat(64) + "\tnosana/nosana-node:latest\tone\n";
        String second = "b".repeat(64) + "\tnosana/nosana-node:latest\ttwo\n";
        String proxy = "c".repeat(64) + "\tnosana/frpc:latest\tfrpc-api-" + "1".repeat(32) + "\n";
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        assertEquals(1, SshDiscovery.collect(DiscoveryPolicy.parseInventory(node + second + proxy), addresses));
        assertEquals(1, addresses.size());
        assertEquals(0, SshDiscovery.collect(DiscoveryPolicy.parseInventory(proxy), new LinkedHashSet<>()));
    }
}
