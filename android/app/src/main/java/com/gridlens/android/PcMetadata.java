package com.gridlens.android;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Dated PC metadata from a trusted SSH session; never a live health or GPU identity claim. */
public final class PcMetadata {
    static final String NAME_COMMAND = "/bin/hostname";
    static final String ADDRESSES_COMMAND = "/bin/hostname -I";
    static final int MAX_IP_ADDRESSES = 32;
    static final int MAX_RESPONSE_BYTES = 8192;
    public final String pcId, pcName;
    public final List<String> ipAddresses;
    public final long observedAtMs;
    public final String gpuTelemetryJson;

    private PcMetadata(String pcId, String pcName, List<String> ipAddresses, long observedAtMs) {
        this(pcId, pcName, ipAddresses, observedAtMs, null);
    }
    private PcMetadata(String pcId, String pcName, List<String> ipAddresses, long observedAtMs, String gpuTelemetryJson) {
        this.pcId = pcId; this.pcName = pcName;
        this.ipAddresses = Collections.unmodifiableList(new ArrayList<>(ipAddresses));
        this.observedAtMs = observedAtMs;
        this.gpuTelemetryJson = gpuTelemetryJson;
    }
    PcMetadata withGpuTelemetry(String json) { return new PcMetadata(pcId, pcName, ipAddresses, observedAtMs, json); }

    static PcMetadata observed(SshDiscovery.Identity identity, String endpoint, String nameOutput,
            String addressOutput, long observedAtMs) {
        if (identity == null || observedAtMs <= 0) throw new IllegalArgumentException("Verified PC metadata required");
        String name = nameOutput == null ? "" : nameOutput.trim();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) name = null;
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        addAddress(addresses, endpoint);
        if (addressOutput != null && addressOutput.getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES) {
            for (String value : addressOutput.trim().split("\\s+")) {
                if (addresses.size() >= MAX_IP_ADDRESSES) break;
                addAddress(addresses, value);
            }
        }
        if (addresses.isEmpty()) return null;
        return new PcMetadata(id(identity), name, new ArrayList<>(addresses), observedAtMs);
    }

    private static void addAddress(LinkedHashSet<String> addresses, String value) {
        if (value == null) return;
        // A scope belongs to the SSH transport's interface, not to portable saved metadata.
        int scope = value.indexOf('%');
        if (scope >= 0) {
            if (!value.substring(0, scope).contains(":") || !value.substring(scope + 1).matches("[A-Za-z0-9_.-]{1,32}")) return;
            value = value.substring(0, scope);
        }
        try {
            InetAddress address = DiscoveryPolicy.numericAddress(value);
            if (address instanceof Inet4Address) {
                int first = address.getAddress()[0] & 255;
                if (first == 0 || first == 127 || first >= 224) return;
            }
            addresses.add(address.getHostAddress());
        } catch (IllegalArgumentException ignored) { /* Unsupported or non-address output stays unknown. */ }
    }

    private static String id(SshDiscovery.Identity identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(identity.keyBase64));
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return value.toString();
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    /** Merge two readings of one pinned PC without assigning names across distinct SSH identities. */
    static PcMetadata merge(PcMetadata initial, PcMetadata next) {
        if (initial == null) return next;
        if (next == null) return initial;
        if (initial.pcId == null || next.pcId == null || !initial.pcId.equals(next.pcId))
            return new PcMetadata(null, null, Collections.emptyList(), Math.max(initial.observedAtMs, next.observedAtMs));
        PcMetadata newer = next.observedAtMs >= initial.observedAtMs ? next : initial;
        // Keep the original complete reading; older names/IPs cannot inherit a newer time.
        return newer;
    }
}
