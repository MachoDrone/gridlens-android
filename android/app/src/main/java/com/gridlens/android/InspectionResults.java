package com.gridlens.android;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/** A privileged retry may reveal another container namespace; keep every verified public address. */
final class InspectionResults {
    static SshDiscovery.Result merge(SshDiscovery.Result initial, SshDiscovery.Result elevated) {
        LinkedHashSet<String> verified = new LinkedHashSet<>(initial.addresses);
        verified.addAll(elevated.addresses);
        // These are unresolved observations, not a deduplicated instance count.
        return new SshDiscovery.Result(elevated.status, new ArrayList<>(verified), initial.unresolved + elevated.unresolved);
    }
    private InspectionResults() {}
}
