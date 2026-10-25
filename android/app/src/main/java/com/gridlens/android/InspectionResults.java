package com.gridlens.android;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** A privileged retry may reveal another container namespace; keep every verified public address. */
final class InspectionResults {
    static SshDiscovery.Result merge(SshDiscovery.Result initial, SshDiscovery.Result elevated) {
        LinkedHashSet<String> verified = new LinkedHashSet<>(initial.addresses);
        verified.addAll(elevated.addresses);
        // These are unresolved observations, not a deduplicated instance count.
        return new SshDiscovery.Result(elevated.status, new ArrayList<>(verified), initial.unresolved + elevated.unresolved,
                PcMetadata.merge(initial.pcMetadata, elevated.pcMetadata), elevated.gpuCollectorStatus != null ? elevated.gpuCollectorStatus : initial.gpuCollectorStatus);
    }
    /** One host can have a PC association only when all current discoveries agree. */
    static Map<String, PcMetadata> metadataByAddress(Iterable<SshDiscovery.Result> results) {
        Map<String, PcMetadata> metadata = new LinkedHashMap<>();
        for (SshDiscovery.Result result : results) {
            if (result.pcMetadata == null) continue;
            for (String address : result.addresses) {
                PcMetadata existing = metadata.get(address);
                metadata.put(address, PcMetadata.merge(existing, result.pcMetadata));
            }
        }
        return metadata;
    }
    private InspectionResults() {}
}
