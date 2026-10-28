package com.gridlens.android;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A host choice narrows enrollment, never the evidence used to enforce SSH failures. */
final class InspectionSelection {
    final List<String> addresses;
    final Map<String, PcMetadata> metadata;
    final Set<String> pcIds;
    final Map<String, SshDiscovery.Result> results;
    final Map<String, SshDiscovery.Result> completeResults;

    private InspectionSelection(Map<String, SshDiscovery.Result> completeResults, Collection<String> selectedAddresses) {
        if (completeResults == null || selectedAddresses == null) throw new IllegalArgumentException("Inspection and host selection required");
        this.completeResults = Collections.unmodifiableMap(new LinkedHashMap<>(completeResults));
        LinkedHashSet<String> discovered = new LinkedHashSet<>();
        for (SshDiscovery.Result result : this.completeResults.values()) {
            if (result == null) throw new IllegalArgumentException("Complete inspection result required");
            discovered.addAll(result.addresses);
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>(selectedAddresses);
        for (String address : selected)
            if (!DiscoveryPolicy.publicAddress(address) || !discovered.contains(address))
                throw new IllegalArgumentException("Select only verified host addresses from this inspection");
        addresses = Collections.unmodifiableList(new ArrayList<>(selected));

        // Establish conflicts before selection: hiding a row cannot resolve competing PC identities.
        Map<String, PcMetadata> allMetadata = InspectionResults.metadataByAddress(this.completeResults.values());
        List<NodeBinding> allBindings = new ArrayList<>();
        for (SshDiscovery.Result result : this.completeResults.values()) allBindings.addAll(result.bindings);
        Map<String, String> bindingKeys = new LinkedHashMap<>();
        for (NodeBinding binding : NodeBinding.unambiguous(allBindings)) bindingKeys.put(binding.address, binding.key());
        Map<String, PcMetadata> selectedMetadata = new LinkedHashMap<>();
        Set<String> selectedPcs = new LinkedHashSet<>();
        for (String address : selected) {
            PcMetadata pc = allMetadata.get(address);
            if (pc != null) {
                selectedMetadata.put(address, pc);
                if (pc.pcId != null) selectedPcs.add(pc.pcId);
            }
        }
        metadata = Collections.unmodifiableMap(selectedMetadata);
        pcIds = Collections.unmodifiableSet(selectedPcs);
        Map<String, SshDiscovery.Result> selectedResults = new LinkedHashMap<>();
        for (Map.Entry<String, SshDiscovery.Result> entry : this.completeResults.entrySet()) {
            SshDiscovery.Result original = entry.getValue();
            List<String> members = new ArrayList<>();
            for (String address : original.addresses) if (selected.contains(address)) members.add(address);
            if (members.isEmpty()) continue;
            List<NodeBinding> bindings = new ArrayList<>();
            for (NodeBinding binding : original.bindings) {
                PcMetadata pc = metadata.get(binding.address);
                if (members.contains(binding.address) && pc != null && pc.pcId != null && original.pcMetadata != null
                        && pc.pcId.equals(original.pcMetadata.pcId) && binding.key().equals(bindingKeys.get(binding.address))) bindings.add(binding);
            }
            selectedResults.put(entry.getKey(), new SshDiscovery.Result(original.status, members, original.unresolved,
                    original.pcMetadata, original.gpuCollectorStatus, bindings));
        }
        results = Collections.unmodifiableMap(selectedResults);
    }

    static InspectionSelection from(Map<String, SshDiscovery.Result> completeResults, Collection<String> selectedAddresses) {
        return new InspectionSelection(completeResults, selectedAddresses);
    }

    boolean includesPc(String pcId) { return pcIds.contains(pcId); }
    SshDiscovery.Result resultFor(String endpoint) { return results.get(endpoint); }
    boolean includesEndpointPc(String endpoint, String pcId) {
        SshDiscovery.Result selected = results.get(endpoint);
        if (selected == null || !includesPc(pcId)) return false;
        for (String address : selected.addresses) if (includesHostOnPc(address, pcId)) return true;
        return false;
    }
    boolean includesHostOnPc(String address, String pcId) {
        PcMetadata pc = metadata.get(address);
        return pc != null && pc.pcId != null && pc.pcId.equals(pcId);
    }
    boolean includesEveryBinding(String pcId, Collection<NodeBinding> bindings) {
        for (NodeBinding binding : bindings) if (!includesHostOnPc(binding.address, pcId)) return false;
        return true;
    }
    void requireCompleteEvidence(Map<String, SshDiscovery.Result> supplied) {
        if (!completeResults.equals(supplied)) throw new IllegalArgumentException("Keep the complete inspection evidence for registration");
    }

    /** Rejection remains relevant even when the failed PC has no selectable public hosts. */
    static boolean credentialWasRejected(SshDiscovery.BatchRequest selected, List<SshDiscovery.BatchRequest> requests,
            Map<String, SshDiscovery.Result> completeResults) {
        for (SshDiscovery.BatchRequest request : requests) {
            SshDiscovery.Result result = completeResults.get(request.candidate.endpointKey());
            if (result != null && result.status == SshDiscovery.Status.AUTH_FAILED
                    && PcIpEnrollment.sameCredential(selected.credential, request.credential)) return true;
        }
        return false;
    }
}
