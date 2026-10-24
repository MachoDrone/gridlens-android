package com.gridlens.android;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class DiscoveryRoutePolicyTest {
    private static DiscoveryRoutePolicy.Prefix prefix(String address, int bits) {
        return new DiscoveryRoutePolicy.Prefix(address, bits, true);
    }
    @Test public void wifiLearnsWholeLocalRangeAndDoesNotInferAnIpv6Sweep() {
        List<String> ranges = DiscoveryRoutePolicy.infer(false,
            Arrays.asList(prefix("10.47.98.1", 16), prefix("fd42::8", 64)),
            Arrays.asList(prefix("0.0.0.0", 0), prefix("::", 0), prefix("fd42::", 64)));
        assertEquals(Collections.singletonList("10.47.98.1/16"), ranges);
        List<String> expanded = DiscoveryPolicy.expandRanges(ranges);
        assertEquals(65534, expanded.size());
        assertTrue(expanded.contains("10.47.255.1"));
    }
    @Test public void fullTunnelDoesNotConfuseOwnVpnAddressWithDestinationNetwork() {
        List<String> ranges = DiscoveryRoutePolicy.infer(true,
            Arrays.asList(prefix("10.7.8.9", 32), prefix("fd42::8", 128)),
            Arrays.asList(prefix("0.0.0.0", 0), prefix("::", 0), prefix("10.7.8.9", 32), prefix("fd42:0:0:0:0:0:0:8", 128)));
        assertTrue("Full tunnel needs a profile or advertisements; no guessed private range", ranges.isEmpty());
        assertTrue(DiscoveryRoutePolicy.infer(false, Collections.singletonList(prefix("10.7.8.9", 32)), Collections.emptyList()).isEmpty());
    }
    @Test public void vpnLearnsOnlySpecificRemoteUnicastRoutesAndExplicitIpv6Hosts() {
        List<String> ranges = DiscoveryRoutePolicy.infer(true, Collections.singletonList(prefix("10.7.8.9", 32)),
            Arrays.asList(prefix("10.0.0.0", 8), prefix("192.168.0.0", 16), prefix("192.0.2.4", 32),
                prefix("fd42::9", 128), prefix("fd42::", 64), prefix("192.168.0.0", 16),
                new DiscoveryRoutePolicy.Prefix("198.51.100.0", 24, false), prefix("127.0.0.1", 32),
                prefix("224.0.0.1", 32), prefix("example.invalid", 24), prefix("192.0.2.0", 33)));
        assertEquals(Arrays.asList("192.168.0.0/16", "192.0.2.4/32", "fd42:0:0:0:0:0:0:9/128"), ranges);
    }
    @Test public void providerAndPhoneAddressChangesDoNotChangeDestinationProfiles() {
        List<DiscoveryRoutePolicy.Prefix> routes = Collections.singletonList(prefix("192.168.8.0", 24));
        List<String> a = DiscoveryRoutePolicy.infer(true, Collections.singletonList(prefix("10.7.8.9", 32)), routes);
        List<String> b = DiscoveryRoutePolicy.infer(true, Collections.singletonList(prefix("100.64.0.2", 32)), routes);
        assertEquals(a, b);
        // Each new scan captures a fresh session even when saved destination ranges match.
        assertFalse(DiscoveryRoutePolicy.sameSession("vpn-A", "vpn-B", "links", "links", "vpn,wifi", "vpn,wifi"));
    }
    @Test public void selectedNetworkDisconnectionIpRoutesAndTransportChangesCancel() {
        assertTrue(DiscoveryRoutePolicy.sameSession("vpn-A", "vpn-A", "links-A", "links-A", "vpn,wifi", "vpn,wifi"));
        assertFalse(DiscoveryRoutePolicy.sameSession("vpn-A", null, "links-A", null, "vpn,wifi", null));
        assertFalse(DiscoveryRoutePolicy.sameSession("vpn-A", "vpn-B", "links-A", "links-A", "vpn,wifi", "vpn,wifi"));
        assertFalse(DiscoveryRoutePolicy.sameSession("vpn-A", "vpn-A", "links-A", "links-B", "vpn,wifi", "vpn,wifi"));
        assertFalse(DiscoveryRoutePolicy.sameSession("vpn-A", "vpn-A", "links-A", "links-A", "vpn,wifi", "vpn,cellular"));
        assertFalse(DiscoveryRoutePolicy.sameSession(null, null, null, null, null, null));
    }
    @Test public void underlyingVpnChangesCancelEvenWhenTransportTypesStayTheSame() {
        Map<String, String> before = new LinkedHashMap<>(); before.put("wifi-A", "address-A/routes-A"); before.put("cell-A", "address-B/routes-B");
        Map<String, String> equal = new LinkedHashMap<>(); equal.put("cell-A", "address-B/routes-B"); equal.put("wifi-A", "address-A/routes-A");
        assertTrue(DiscoveryRoutePolicy.samePhysicalNetworks(before, equal));
        Map<String, String> moved = new LinkedHashMap<>(before); moved.remove("wifi-A"); moved.put("wifi-B", "address-A/routes-A");
        assertFalse(DiscoveryRoutePolicy.samePhysicalNetworks(before, moved));
        Map<String, String> renumbered = new LinkedHashMap<>(before); renumbered.put("wifi-A", "address-C/routes-A");
        assertFalse(DiscoveryRoutePolicy.samePhysicalNetworks(before, renumbered));
        Map<String, String> lost = new LinkedHashMap<>(before); lost.remove("wifi-A");
        assertFalse(DiscoveryRoutePolicy.samePhysicalNetworks(before, lost));
        Map<String, String> added = new LinkedHashMap<>(before); added.put("ethernet-C", "address-C/routes-C");
        assertFalse(DiscoveryRoutePolicy.samePhysicalNetworks(before, added));
        assertFalse(DiscoveryRoutePolicy.samePhysicalNetworks(before, null));
    }
}
