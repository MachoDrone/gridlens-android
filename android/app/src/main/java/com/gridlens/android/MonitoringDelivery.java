package com.gridlens.android;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Each public source crosses the existing observation API independently. */
final class MonitoringDelivery {
    interface Sender { void send(String source, String metadataJson, String sourceJson) throws Exception; }
    static Set<String> deliver(String pcId, long checkedAtMs, SshDiscovery.MonitoringResult result,
            BooleanSupplier permitted, Sender sender) {
        Set<String> accepted = new LinkedHashSet<>();
        String[] names = {"network", "rewards", "gpu"};
        String[] values = {result.networkJson, result.rewardsJson, result.gpuJson};
        boolean hasSource = false;
        for (int i = 0; i < names.length; i++) {
            if (values[i] == null) continue;
            hasSource = true;
            if (!permitted.getAsBoolean()) return accepted;
            try { sender.send(names[i], update(pcId, checkedAtMs, result.binding, result.status), values[i]); accepted.add(names[i]); }
            catch (Exception rejected) { /* A malformed/rejected source cannot reject its siblings. */ }
        }
        if (!hasSource && permitted.getAsBoolean()) try { sender.send("monitoring", update(pcId, checkedAtMs, result.binding, result.status), null); }
        catch (Exception rejected) { /* Native permissions remain authoritative if delivery is unavailable. */ }
        return accepted;
    }
    static String update(String pcId, long checkedAtMs, NodeBinding binding, String status) {
        if (pcId == null || !pcId.matches("[a-f0-9]{64}") || binding == null || checkedAtMs <= 0 || checkedAtMs > PublicIpReader.MAX_SAFE_INTEGER
                || status == null || !status.matches("[a-z]+(?:-[a-z]+)*")) throw new IllegalArgumentException("Invalid public observation envelope");
        // Only validated public identifiers enter this envelope. Source JSON stays
        // separate so the sender must insert a parsed nested object, never raw text.
        return "{\"address\":\"" + binding.address + "\",\"binding\":{\"runtime\":\"" + binding.runtime
                + "\",\"wrapperId\":" + (binding.wrapperId == null ? "null" : "\"" + binding.wrapperId + "\"")
                + ",\"nodeContainerId\":\"" + binding.nodeContainerId + "\",\"pcId\":\"" + pcId
                + "\"},\"monitoring\":{\"status\":\"" + status + "\",\"checkedAtMs\":" + checkedAtMs + "}}";
    }
    private MonitoringDelivery() {}
}
