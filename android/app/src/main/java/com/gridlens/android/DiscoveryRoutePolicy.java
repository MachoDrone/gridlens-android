package com.gridlens.android;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure policy over Android-provided route facts; never queries DNS or invents ranges. */
final class DiscoveryRoutePolicy {
    static final class Prefix {
        final String address;
        final int prefixLength;
        final boolean unicast;
        Prefix(String address, int prefixLength, boolean unicast) {
            this.address = address; this.prefixLength = prefixLength; this.unicast = unicast;
        }
    }

    static List<String> infer(boolean vpn, List<Prefix> links, List<Prefix> routes) {
        Set<String> local = new LinkedHashSet<>();
        LinkedHashSet<String> ranges = new LinkedHashSet<>();
        for (Prefix link : links) {
            InetAddress address = numeric(link.address);
            if (address == null) continue;
            local.add(address.getHostAddress());
            // A local /32 identifies only this phone, not a discoverable LAN.
            if (!vpn && address instanceof Inet4Address && link.prefixLength >= 16 && link.prefixLength < 32)
                ranges.add(address.getHostAddress() + "/" + link.prefixLength);
        }
        for (Prefix route : routes) {
            if (!route.unicast) continue;
            InetAddress address = numeric(route.address);
            if (address == null || local.contains(address.getHostAddress())) continue;
            if ((vpn && address instanceof Inet4Address && route.prefixLength >= 16 && route.prefixLength <= 32)
                    || (address instanceof Inet6Address && route.prefixLength == 128))
                ranges.add(address.getHostAddress() + "/" + route.prefixLength);
        }
        return Collections.unmodifiableList(new ArrayList<>(ranges));
    }

    private static InetAddress numeric(String address) {
        try { return DiscoveryPolicy.numericAddress(address); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    static boolean sameSession(String selectedId, String currentId, String selectedLinks, String currentLinks,
            String selectedTransports, String currentTransports) {
        return selectedId != null && selectedLinks != null && selectedTransports != null
            && selectedId.equals(currentId) && selectedLinks.equals(currentLinks)
            && selectedTransports.equals(currentTransports);
    }

    /** Conservative cancellation: a VPN may move between indistinguishable transports. */
    static boolean samePhysicalNetworks(Map<String, String> before, Map<String, String> now) {
        return before != null && now != null && Objects.equals(before, now);
    }
    private DiscoveryRoutePolicy() { }
}
