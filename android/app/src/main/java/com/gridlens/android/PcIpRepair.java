package com.gridlens.android;

import android.app.Activity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** A native, explicitly selected PC IP check; no node discovery, wallet or GPU authority. */
final class PcIpRepair {
    static final class Failure extends Exception {
        final String status;
        Failure(String status) { super(status); this.status = status; }
    }
    static final class Endpoint {
        final JSONObject endpoint, expectedTarget;
        final String credentialId;
        final boolean needsApproval;
        Endpoint(JSONObject endpoint, JSONObject target, String credentialId, boolean needsApproval) throws Exception {
            this.endpoint = new JSONObject(endpoint.toString());
            this.expectedTarget = target == null ? null : new JSONObject(target.toString());
            this.credentialId = credentialId; this.needsApproval = needsApproval;
        }
        String label() {
            String profile = DiscoveryPolicy.safeName(endpoint.optString("profileName"));
            return endpoint.optString("address") + ":" + endpoint.optInt("port") + (profile.isEmpty() ? "" : " — " + profile);
        }
    }
    static final class Plan {
        final String fleetId, pcId, pcName;
        final List<Endpoint> endpoints;
        Plan(String fleetId, String pcId, String pcName, List<Endpoint> endpoints) {
            this.fleetId = fleetId; this.pcId = pcId; this.pcName = pcName; this.endpoints = java.util.Collections.unmodifiableList(endpoints);
        }
    }
    static final class Result {
        final String status;
        final PublicIpReader.Observation observation;
        final boolean delivered, measured, connectionVerified;
        final long retryAtMs;
        Result(String status, PublicIpReader.Observation observation, boolean delivered, boolean measured, boolean verified, long retryAtMs) {
            this.status = status; this.observation = observation; this.delivered = delivered; this.measured = measured;
            this.connectionVerified = verified; this.retryAtMs = retryAtMs;
        }
    }
    private final DiscoveryStore store;
    private final MonitoringController monitoring;
    private final PublicNetworkCache cache;
    private final String origin, token;
    PcIpRepair(Activity activity, DiscoveryStore store, MonitoringController monitoring, String origin, String token) {
        this.store = store; this.monitoring = monitoring; this.cache = monitoring.pcIpCache(); this.origin = origin; this.token = token;
    }
    static boolean validScope(String fleet, String pc) {
        return fleet != null && fleet.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                && pc != null && pc.matches("[a-f0-9]{64}");
    }
    Plan inspect(String fleet, String pc) throws Exception {
        if (!validScope(fleet, pc)) throw new Failure("invalid-scope");
        if (monitoring.storageNeedsRecovery() || cache.storageNeedsRecovery()) throw new Failure("permission-storage-failed");
        JSONObject metadata = associatedPc(fleet, pc);
        if (metadata == null) throw new Failure("unknown-pc");
        JSONArray targets = store.monitoringTargets();
        Map<String, JSONObject> matchingTargets = new LinkedHashMap<>();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target == null || !fleet.equals(target.optString("fleetId")) || !pc.equals(target.optString("pcId"))) continue;
            try { MonitoringController.validatePcTarget(target); }
            catch (Exception invalid) { continue; }
            matchingTargets.put(key(target), target);
        }
        JSONArray known = store.knownEndpoints();
        List<Endpoint> candidates = new ArrayList<>(), approved = new ArrayList<>(), preferred = new ArrayList<>();
        JSONArray addresses = metadata.optJSONArray("ipAddresses");
        for (int i = 0; i < known.length(); i++) {
            JSONObject endpoint = known.getJSONObject(i);
            SshDiscovery.Identity identity = identity(endpoint);
            if (!pc.equals(MonitoringController.pcId(identity))) continue;
            JSONObject exactTarget = matchingTargets.get(key(endpoint));
            JSONObject target = exactTarget;
            if (target == null) for (JSONObject saved : matchingTargets.values()) {
                if (target == null || target.optBoolean("aliasReplaced") && !saved.optBoolean("aliasReplaced")
                        || !saved.optBoolean("aliasReplaced") && !target.optBoolean("aliasReplaced") && saved.optBoolean("approved") && !target.optBoolean("approved")) target = saved;
            }
            String credential = target == null ? null : target.optString("credentialId", null);
            if (credential == null || credential.isEmpty() || store.credential(credential) == null) credential = store.override(identity.keyBase64);
            if (credential != null && store.credential(credential) == null) credential = null;
            boolean allowed = exactTarget != null && !monitoring.storageNeedsRecovery() && MonitoringController.permittedNetworkObservation(store, target);
            Endpoint value = new Endpoint(endpoint, target, credential, !allowed);
            candidates.add(value);
            if (allowed) approved.add(value);
            if (addresses != null) for (int j = 0; j < addresses.length(); j++) if (endpoint.getString("address").equals(addresses.optString(j))) { preferred.add(value); break; }
        }
        if (candidates.isEmpty()) throw new Failure("saved-endpoint-missing");
        // Keep valid approved routing; otherwise prefer a discovered PC interface.
        // More than one remaining candidate is deliberately a native choice.
        List<Endpoint> selected = new ArrayList<>();
        selected.addAll(approved);
        for (Endpoint value : preferred) if (!selected.contains(value)) selected.add(value);
        for (Endpoint value : candidates) if (!selected.contains(value)) selected.add(value);
        String name = metadata.optString("pcName", "PC");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,119}")) name = "PC";
        return new Plan(fleet, pc, name, selected);
    }
    Result execute(Plan plan, Endpoint endpoint, String credentialId, boolean explicitApproval,
            DiscoveryNetwork network, BooleanSupplier permitted) {
        boolean[] measured = {false}, verified = {false};
        JSONObject[] approvedTarget = {endpoint.expectedTarget};
        boolean needsApproval = endpoint.needsApproval || !java.util.Objects.equals(endpoint.credentialId, credentialId);
        boolean oneTimeConnection = oneTimeConnection(endpoint, credentialId);
        try {
            if (needsApproval && !explicitApproval) return result("confirmation-required", null, false, false, false, 0);
            if (!permitted.getAsBoolean() || network.isCancelled()) return result("cancelled", null, false, false, false, 0);
            if (monitoring.storageNeedsRecovery() || cache.storageNeedsRecovery()) throw new Failure("permission-storage-failed");
            if (associatedPc(plan.fleetId, plan.pcId) == null) throw new Failure("unknown-pc");
            JSONObject credential = store.credential(credentialId);
            if (credential == null) throw new Failure("credentials-required");
            String credentialSnapshot = credential.toString();
            BooleanSupplier consent = () -> permitted.getAsBoolean() && !network.isCancelled()
                    && connectionUnchanged(plan, endpoint, credentialId, credentialSnapshot, approvedTarget[0], needsApproval);
            if (!consent.getAsBoolean()) throw new Failure("connection-changed");
            long now = System.currentTimeMillis();
            long sourceDeadline = Math.max(cache.nextReadAt(plan.pcId), endpoint.expectedTarget == null ? 0 : endpoint.expectedTarget.optLong("networkRetryAtMs"));
            // A cache hit/provider wait is never proof that a revoked or missing SSH
            // connection was verified. Check authentication only, without invoking curl.
            if (needsApproval && sourceDeadline > now) {
                try (SshDiscovery.Credential login = DiscoveryController.decodeCredential(credential)) {
                    SshDiscovery.NetworkResult checked = SshDiscovery.verifyPcConnection(network, candidate(endpoint.endpoint), identity(endpoint.endpoint), login, consent);
                    handleConnectionResult(checked, endpoint, credentialId, credentialSnapshot);
                    if (!"active".equals(checked.status)) return result(checked.status, null, false, false, false, cache.nextReadAt(plan.pcId));
                    verified[0] = true;
                }
            }
            PublicIpReader.Observation observation = sourceDeadline > System.currentTimeMillis() ? cache.peek(plan.pcId) : cache.getOrRead(plan.pcId, () -> {
                if (!consent.getAsBoolean()) throw new PublicNetworkCache.Aborted();
                try (SshDiscovery.Credential login = DiscoveryController.decodeCredential(credential)) {
                    SshDiscovery.NetworkResult read = SshDiscovery.readNetwork(network, candidate(endpoint.endpoint), identity(endpoint.endpoint), login, consent);
                    if (read.observation != null && read.observation.retryAtMs > 0) cache.preserveRetry(plan.pcId, read.observation.retryAtMs);
                    handleConnectionResult(read, endpoint, credentialId, credentialSnapshot);
                    if (!"active".equals(read.status)) throw new Failure(read.status);
                    if (!consent.getAsBoolean()) throw new PublicNetworkCache.Aborted();
                    verified[0] = true; measured[0] = read.observation != null;
                    return read.observation;
                }
            });
            if (!consent.getAsBoolean() || associatedPc(plan.fleetId, plan.pcId) == null) return result("cancelled", null, false, measured[0], verified[0], 0);
            if (needsApproval && !oneTimeConnection) {
                if (!verified[0]) return result("confirmation-required", observation, false, measured[0], false, cache.nextReadAt(plan.pcId));
                JSONObject replacement = pcOnlyTarget(plan, endpoint, credentialId);
                if (!store.approveDirectPcIp(endpoint.expectedTarget, replacement, credentialSnapshot, permitted))
                    return result("connection-changed", observation, false, measured[0], true, cache.nextReadAt(plan.pcId));
                approvedTarget[0] = store.findMonitoringTarget(replacement.getString("id"));
                monitoring.acceptDirectPcIpApproval(approvedTarget[0]);
            }
            if (observation == null) return result(cache.providerRetryAt(plan.pcId) > System.currentTimeMillis() ? "provider-wait" : "retry-wait",
                    null, false, measured[0], verified[0], Math.max(sourceDeadline, cache.nextReadAt(plan.pcId)));
            if (observation.observedAtMs > System.currentTimeMillis()) return result("clock-untrusted", observation, false, measured[0], verified[0], cache.nextReadAt(plan.pcId));
            if (!consent.getAsBoolean() || !MonitoringController.permittedNetworkObservation(store, approvedTarget[0]))
                return result("cancelled", null, false, measured[0], verified[0], 0);
            long delivery = cache.nextDeliveryAt(plan.fleetId, plan.pcId);
            if (delivery > System.currentTimeMillis() && cache.pendingDelivery(plan.fleetId, plan.pcId))
                return result("delivery-wait", observation, false, measured[0], verified[0], delivery);
            cache.deliveryAttempt(plan.fleetId, plan.pcId);
            request("/mobile/observations", new JSONObject().put("schema", 1).put("fleetId", plan.fleetId)
                    .put("updates", new JSONArray().put(new JSONObject().put("pcId", plan.pcId).put("network", new JSONObject(observation.json())))), 64 * 1024);
            if (!consent.getAsBoolean() || !MonitoringController.permittedNetworkObservation(store, approvedTarget[0]))
                return result("cancelled", observation, false, measured[0], verified[0], 0);
            PublicIpReader.Observation received = deliveredReading(plan, observation);
            if (received == null) return result("delivery-unconfirmed", observation, false, measured[0], verified[0], cache.nextDeliveryAt(plan.fleetId, plan.pcId));
            if (!consent.getAsBoolean() || !MonitoringController.permittedNetworkObservation(store, approvedTarget[0]))
                return result("cancelled", observation, false, measured[0], verified[0], 0);
            if (received.retryAtMs > 0) cache.preserveRetry(plan.pcId, received.retryAtMs);
            cache.delivered(plan.fleetId, plan.pcId, observation);
            boolean newlyMeasured = measured[0] && sameSourceReading(received, observation);
            return result(received.publicIp == null ? "lookup-failed" : newlyMeasured ? "measured" : "cached", received, true, measured[0], verified[0], cache.nextReadAt(plan.pcId));
        } catch (PublicNetworkCache.Aborted cancelled) { return result("cancelled", null, false, measured[0], verified[0], 0); }
        catch (Failure failure) { return result(failure.status, cache.peek(plan.pcId), false, measured[0], verified[0], cache.nextReadAt(plan.pcId)); }
        catch (Exception unavailable) { return result(!permitted.getAsBoolean() || network.isCancelled() ? "cancelled" : "delivery-or-storage-failed", cache.peek(plan.pcId), false, measured[0], verified[0], 0); }
    }
    /** Choosing another connection cannot rewrite or pause a healthy node target. */
    static boolean oneTimeConnection(Endpoint endpoint, String credentialId) {
        JSONObject target = endpoint.expectedTarget;
        return (endpoint.needsApproval || !java.util.Objects.equals(endpoint.credentialId, credentialId)) && target != null
                && target.optBoolean("approved") && !target.optBoolean("paused") && !target.optBoolean("aliasReplaced")
                && MonitoringPolicy.pcNetworkPermitted(true, false, target.optString("status"));
    }
    private static boolean sameSourceReading(PublicIpReader.Observation first, PublicIpReader.Observation second) {
        return first.observedAtMs == second.observedAtMs && java.util.Objects.equals(first.publicIp, second.publicIp)
                && java.util.Objects.equals(first.privateIp, second.privateIp) && java.util.Objects.equals(first.publicObservedAtMs, second.publicObservedAtMs)
                && java.util.Objects.equals(first.privateObservedAtMs, second.privateObservedAtMs) && java.util.Objects.equals(first.source, second.source)
                && java.util.Objects.equals(first.publicFailure, second.publicFailure) && java.util.Objects.equals(first.privateFailure, second.privateFailure)
                && java.util.Objects.equals(first.httpStatus, second.httpStatus);
    }
    private PublicIpReader.Observation deliveredReading(Plan plan, PublicIpReader.Observation sent) throws Exception {
        JSONObject response = request("/mobile/observations?fleet=" + plan.fleetId, null, 8 * 1024 * 1024);
        if (response.optInt("schema") != 1 || !plan.fleetId.equals(response.optString("fleetId")) || !response.isNull("error")) return null;
        JSONArray values = response.getJSONArray("pcNetworks");
        if (values.length() > 2048) return null;
        PublicIpReader.Observation found = null;
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if (!plan.pcId.equals(value.optString("pcId"))) continue;
            if (found != null) return null;
            found = MonitoringController.receiverNetwork(value.getJSONObject("network"));
        }
        if (found == null || found.observedAtMs < sent.observedAtMs || found.observedAtMs > System.currentTimeMillis()) return null;
        if (found.observedAtMs == sent.observedAtMs && (!java.util.Objects.equals(found.publicIp, sent.publicIp)
                || !java.util.Objects.equals(found.privateIp, sent.privateIp)
                || !java.util.Objects.equals(found.publicObservedAtMs, sent.publicObservedAtMs)
                || !java.util.Objects.equals(found.privateObservedAtMs, sent.privateObservedAtMs)
                || !java.util.Objects.equals(found.source, sent.source))) return null;
        return found;
    }
    private boolean connectionUnchanged(Plan plan, Endpoint endpoint, String credentialId, String credentialSnapshot, JSONObject target, boolean explicit) {
        try {
            if (monitoring.storageNeedsRecovery() || cache.storageNeedsRecovery()) return false;
            if (!identity(endpoint.endpoint).keyBase64.equals(store.trustedKey(endpoint.endpoint.getString("profileId"), candidate(endpoint.endpoint).endpointKey()))) return false;
            JSONObject credential = store.credential(credentialId);
            if (credential == null || !credentialSnapshot.equals(credential.toString())) return false;
            if (target == null) return store.findMonitoringPc(plan.fleetId, plan.pcId) == null;
            JSONObject current = store.findMonitoringTarget(target.getString("id"));
            if (current == null) return false;
            if (explicit) return current.toString().equals(target.toString());
            return MonitoringController.permittedNetworkObservation(store, target);
        } catch (Exception unavailable) { return false; }
    }
    private void handleConnectionResult(SshDiscovery.NetworkResult value, Endpoint endpoint, String credentialId, String credentialSnapshot) throws Exception {
        JSONObject target = endpoint.expectedTarget;
        try {
            if ("credentials-required".equals(value.status)) {
                store.suspendDirectPcCredential(credentialId, credentialSnapshot);
                monitoring.rejectDirectPcCredential(credentialId);
            } else if ("identity-changed".equals(value.status) && target != null
                    && candidate(target).endpointKey().equals(candidate(endpoint.endpoint).endpointKey()))
                store.restrictPcConnection(target, "identity-changed");
        } catch (Exception failedRestriction) {
            monitoring.failDirectPcPermissionStorage(); throw new Failure("permission-storage-failed");
        }
    }
    private JSONObject pcOnlyTarget(Plan plan, Endpoint endpoint, String credentialId) throws Exception {
        JSONObject target = endpoint.expectedTarget == null ? new JSONObject().put("id", UUID.randomUUID().toString())
                .put("authorizationId", UUID.randomUUID().toString()).put("bindings", new JSONArray()).put("rewardsAuthorized", false)
                .put("rewardsSuspended", new JSONArray()).put("rewardsVerified", new JSONArray()) : new JSONObject(endpoint.expectedTarget.toString());
        return target.put("fleetId", plan.fleetId).put("pcId", plan.pcId).put("profileId", endpoint.endpoint.getString("profileId"))
                .put("address", endpoint.endpoint.getString("address")).put("port", endpoint.endpoint.getInt("port"))
                .put("keyBase64", endpoint.endpoint.getString("keyBase64")).put("algorithm", endpoint.endpoint.getString("algorithm"))
                .put("credentialId", credentialId).put("approved", true).put("paused", true).put("status", "binding-changed").put("setupPending", false);
    }
    private JSONObject associatedPc(String fleet, String pc) throws Exception {
        JSONArray fleets = request("/mobile/fleets", null, 4 * 1024 * 1024).getJSONArray("fleets");
        for (int i = 0; i < fleets.length(); i++) {
            JSONObject value = fleets.getJSONObject(i);
            if (!fleet.equals(value.getString("id"))) continue;
            JSONArray hosts = value.getJSONArray("hosts");
            for (int j = 0; j < hosts.length(); j++) {
                JSONObject metadata = hosts.getJSONObject(j).optJSONObject("pcMetadata");
                if (metadata != null && pc.equals(metadata.optString("pcId"))) return metadata;
            }
        }
        return null;
    }
    private JSONObject request(String path, JSONObject body, int maximum) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("X-GridLens-Token", token);
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            if (connection.getResponseCode() != 200) throw new Failure(body == null ? "saved-fleet-unavailable" : "local-delivery-rejected");
            try (InputStream input = connection.getInputStream()) { return new JSONObject(new String(NodeRuntime.readBounded(input, maximum), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }
    private static Result result(String status, PublicIpReader.Observation observation, boolean delivered, boolean measured, boolean verified, long retry) {
        return new Result(status, observation, delivered, measured, verified, retry);
    }
    private static String key(JSONObject endpoint) throws Exception { return endpoint.getString("profileId") + "|" + candidate(endpoint).endpointKey(); }
    private static DiscoveryPolicy.Candidate candidate(JSONObject endpoint) throws Exception { return new DiscoveryPolicy.Candidate(endpoint.getString("address"), endpoint.getInt("port"), ""); }
    private static SshDiscovery.Identity identity(JSONObject endpoint) throws Exception { return new SshDiscovery.Identity(endpoint.getString("keyBase64"), endpoint.getString("algorithm")); }
}
