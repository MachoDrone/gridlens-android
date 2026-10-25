package com.gridlens.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Exact public identity observed from one immutable running node container. */
public final class NodeBinding {
    public final String runtime, wrapperId, nodeContainerId, address;
    public NodeBinding(String runtime, String wrapperId, String nodeContainerId, String address) {
        if (!("docker".equals(runtime) || "podman".equals(runtime))
                || nodeContainerId == null || !nodeContainerId.matches("[a-f0-9]{64}")
                || wrapperId != null && (!wrapperId.matches("[a-f0-9]{64}") || !"podman".equals(runtime))
                || !DiscoveryPolicy.publicAddress(address)) throw new IllegalArgumentException("Invalid exact node binding");
        this.runtime = runtime; this.wrapperId = wrapperId; this.nodeContainerId = nodeContainerId; this.address = address;
    }
    String key() { return runtime + ":" + Objects.toString(wrapperId, "") + ":" + nodeContainerId; }
    static List<NodeBinding> unambiguous(List<NodeBinding> input) {
        LinkedHashMap<String, NodeBinding> byAddress = new LinkedHashMap<>();
        LinkedHashSet<String> conflicts = new LinkedHashSet<>();
        for (NodeBinding binding : input) {
            if (conflicts.contains(binding.address)) continue;
            NodeBinding previous = byAddress.get(binding.address);
            if (previous != null && !previous.key().equals(binding.key())) {
                byAddress.remove(binding.address); conflicts.add(binding.address);
            } else byAddress.put(binding.address, binding);
        }
        return new ArrayList<>(byAddress.values());
    }
}
