package com.gridlens.android;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Native-only state. Neither filenames nor records are sent to the Node server or WebView. */
final class DiscoveryStore {
    private static final String ALIAS = "gridlens.discovery.credentials.v1";
    private final String alias;
    private final AtomicFile settings;
    private final AtomicFile secrets;
    private JSONObject state;
    private JSONObject credentials;
    private boolean credentialFailure;

    DiscoveryStore(Context context) throws Exception { this(context, "discovery"); }
    DiscoveryStore(Context context, String namespace) throws Exception {
        if (!namespace.matches("[a-z0-9-]{1,48}")) throw new IllegalArgumentException("Invalid storage namespace");
        alias = namespace.equals("discovery") ? ALIAS : ALIAS + "." + namespace;
        File dir = new File(context.getNoBackupFilesDir(), namespace);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Storage unavailable");
        settings = new AtomicFile(new File(dir, "profiles.json"));
        secrets = new AtomicFile(new File(dir, "credentials.aesgcm"));
        state = readPublic();
    }
    private JSONObject readPublic() throws Exception {
        if (!settings.getBaseFile().exists() && !new File(settings.getBaseFile() + ".bak").exists())
            return new JSONObject().put("profiles", new JSONArray()).put("trust", new JSONObject());
        try (java.io.InputStream input = settings.openRead()) {
            return new JSONObject(new String(NodeRuntime.readBounded(input, 1024 * 1024), StandardCharsets.UTF_8));
        }
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        if (!create || secrets.getBaseFile().exists() || new File(secrets.getBaseFile() + ".bak").exists())
            throw new java.security.GeneralSecurityException("Credential key unavailable; explicitly forget credentials before replacing it");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }
    private JSONObject credentials() throws Exception {
        if (credentialFailure) throw new java.security.GeneralSecurityException("Saved credentials are unavailable; forget them before saving replacements.");
        if (credentials != null) return credentials;
        if (!secrets.getBaseFile().exists() && !new File(secrets.getBaseFile() + ".bak").exists()) {
            credentials = new JSONObject().put("profiles", new JSONArray()).put("overrides", new JSONObject());
            return credentials;
        }
        byte[] clear = null;
        try (java.io.InputStream input = secrets.openRead()) {
            clear = CredentialCipher.decrypt(key(false), NodeRuntime.readBounded(input, 2 * 1024 * 1024));
            credentials = new JSONObject(new String(clear, StandardCharsets.UTF_8));
            return credentials;
        } catch (Exception failure) { credentialFailure = true; throw failure; }
        finally { if (clear != null) Arrays.fill(clear, (byte) 0); }
    }
    synchronized JSONArray profiles() throws Exception { return new JSONArray(state.getJSONArray("profiles").toString()); }
    synchronized String saveProfile(String id, String name, JSONArray ranges, JSONArray ports) throws Exception {
        if (name.trim().isEmpty() || name.length() > 80 || ranges.length() > 32 || ports.length() < 1 || ports.length() > 8)
            throw new IllegalArgumentException("Invalid network profile");
        String target = id == null ? UUID.randomUUID().toString() : id;
        JSONObject candidate = new JSONObject(state.toString());
        JSONArray all = candidate.getJSONArray("profiles");
        JSONObject profile = new JSONObject().put("id", target).put("name", name.trim()).put("ranges", ranges).put("ports", ports);
        boolean replaced = false;
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("id").equals(target)) { all.put(i, profile); replaced = true; break; }
        if (!replaced) { if (all.length() >= 100) throw new IllegalArgumentException("Too many network profiles"); all.put(profile); }
        save(settings, candidate.toString().getBytes(StandardCharsets.UTF_8)); state = candidate;
        return target;
    }
    synchronized void deleteProfile(String id) throws Exception {
        JSONObject candidate = new JSONObject(state.toString());
        JSONArray all = candidate.getJSONArray("profiles");
        for (int i = all.length() - 1; i >= 0; i--) if (all.getJSONObject(i).getString("id").equals(id)) all.remove(i);
        candidate.getJSONObject("trust").remove(id);
        save(settings, candidate.toString().getBytes(StandardCharsets.UTF_8)); state = candidate;
    }
    synchronized String trustedKey(String profileId, String endpoint) throws Exception {
        JSONObject profile = state.getJSONObject("trust").optJSONObject(profileId);
        return profile == null ? null : profile.optString(endpoint, null);
    }
    synchronized JSONArray knownEndpoints() throws Exception {
        JSONArray result = new JSONArray();
        JSONObject trust = state.getJSONObject("trust");
        JSONArray profiles = state.getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            JSONObject endpoints = trust.optJSONObject(profile.getString("id"));
            if (endpoints == null) continue;
            java.util.Iterator<String> keys = endpoints.keys();
            while (keys.hasNext()) {
                String endpoint = keys.next();
                int split = endpoint.lastIndexOf("]:");
                if (!endpoint.startsWith("[") || split < 2) continue;
                String address = endpoint.substring(1, split);
                int port = Integer.parseInt(endpoint.substring(split + 2));
                DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate(address, port, "");
                String key = endpoints.getString(endpoint);
                com.jcraft.jsch.HostKey parsed = new com.jcraft.jsch.HostKey("saved", java.util.Base64.getDecoder().decode(key));
                new SshDiscovery.Identity(key, parsed.getType());
                result.put(new JSONObject().put("profileId", profile.getString("id")).put("profileName", profile.getString("name"))
                        .put("address", candidate.address).put("port", candidate.port).put("keyBase64", key).put("algorithm", parsed.getType()));
            }
        }
        return result;
    }
    synchronized void trust(String profileId, String endpoint, String key) throws Exception {
        JSONObject candidate = new JSONObject(state.toString());
        JSONObject all = candidate.getJSONObject("trust");
        JSONObject profile = all.optJSONObject(profileId);
        if (profile == null) { profile = new JSONObject(); all.put(profileId, profile); }
        String prior = profile.optString(endpoint, null);
        if (prior != null && !prior.equals(key)) throw new SecurityException("SSH identity changed");
        profile.put(endpoint, key);
        save(settings, candidate.toString().getBytes(StandardCharsets.UTF_8)); state = candidate;
    }
    synchronized JSONArray credentialProfiles() throws Exception {
        JSONArray profiles = credentials().getJSONArray("profiles"), summaries = new JSONArray();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject c = profiles.getJSONObject(i);
            summaries.put(new JSONObject().put("id", c.getString("id")).put("name", c.getString("name")).put("username", c.getString("username")));
        }
        return summaries;
    }
    synchronized JSONObject credential(String id) throws Exception {
        JSONArray profiles = credentials().getJSONArray("profiles");
        for (int i = 0; i < profiles.length(); i++) if (profiles.getJSONObject(i).getString("id").equals(id)) return new JSONObject(profiles.getJSONObject(i).toString());
        return null;
    }
    synchronized String override(String identity) throws Exception { return credentials().getJSONObject("overrides").optString(identity, null); }
    synchronized String saveCredential(JSONObject value, String identity) throws Exception {
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray profiles = candidate.getJSONArray("profiles");
        if (profiles.length() >= 100) throw new IllegalArgumentException("Too many credentials");
        JSONObject record = new JSONObject(value.toString());
        String id = UUID.randomUUID().toString(); record.put("id", id); profiles.put(record);
        if (identity != null) candidate.getJSONObject("overrides").put(identity, id);
        saveCredentials(candidate); return id;
    }
    synchronized void setOverride(String identity, String credentialId) throws Exception {
        JSONObject candidate = new JSONObject(credentials().toString());
        candidate.getJSONObject("overrides").put(identity, credentialId); saveCredentials(candidate);
    }
    synchronized JSONArray monitoringTargets() throws Exception {
        JSONArray targets = credentials().optJSONArray("monitoringTargets");
        return targets == null ? new JSONArray() : new JSONArray(targets.toString());
    }
    /** Resume only the unchanged approval that was explicitly checked; source policy fields survive. */
    synchronized JSONArray resumeVerifiedConnections(JSONArray expected, java.util.function.BooleanSupplier stillAuthorized) throws Exception {
        JSONArray resumed = new JSONArray();
        if (!stillAuthorized.getAsBoolean()) return resumed;
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return resumed;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.getJSONObject(i);
            if (!MonitoringRecovery.eligible(current.optBoolean("approved"), current.optBoolean("paused"), current.optString("status"), current.optBoolean("aliasReplaced"))) continue;
            for (int j = 0; j < expected.length(); j++) {
                JSONObject prior = expected.getJSONObject(j);
                if (!sameRecoveryApproval(current, prior)) continue;
                String endpoint = new DiscoveryPolicy.Candidate(current.getString("address"), current.getInt("port"), "").endpointKey();
                if (!current.getString("keyBase64").equals(trustedKey(current.getString("profileId"), endpoint))
                        || credential(current.getString("credentialId")) == null) continue;
                // SSH recovery never renews source authorization: a late denial from that
                // same source approval must still apply. Only the recovery receipt rotates.
                current.put("paused", false).put("status", "active").put("recoveryGeneration", UUID.randomUUID().toString())
                        .put("lastConnectionCheckStatus", "active").put("lastConnectionCheckAtMs", System.currentTimeMillis());
                resumed.put(new JSONObject(current.toString())); break;
            }
        }
        if (!stillAuthorized.getAsBoolean()) return new JSONArray();
        if (resumed.length() > 0) saveCredentials(candidate);
        return resumed;
    }
    private static boolean sameRecoveryApproval(JSONObject current, JSONObject expected) throws Exception {
        for (String key : new String[]{"id", "fleetId", "pcId", "authorizationId", "credentialId", "profileId", "address", "keyBase64", "algorithm"})
            if (!current.getString(key).equals(expected.getString(key))) return false;
        return current.getInt("port") == expected.getInt("port")
                && current.optString("recoveryGeneration").equals(expected.optString("recoveryGeneration"))
                && current.getJSONArray("bindings").toString().equals(expected.getJSONArray("bindings").toString())
                && current.optBoolean("sudoApproved") == expected.optBoolean("sudoApproved")
                && current.optString("sudoPassword").equals(expected.optString("sudoPassword"));
    }
    synchronized void recordConnectionCheckFailure(JSONObject expected, String status, long checkedAtMs) throws Exception {
        if (!java.util.Arrays.asList("credentials-required", "identity-changed", "binding-changed", "unreachable", "unsupported").contains(status)) return;
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets"); if (targets == null) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.getJSONObject(i);
            if (!MonitoringRecovery.eligible(current.optBoolean("approved"), current.optBoolean("paused"), current.optString("status"), current.optBoolean("aliasReplaced"))
                    || !sameRecoveryApproval(current, expected)) continue;
            current.put("lastConnectionCheckStatus", status).put("lastConnectionCheckAtMs", checkedAtMs);
            if ("identity-changed".equals(status) || "binding-changed".equals(status)) current.put("status", status);
            saveCredentials(candidate); return;
        }
    }
    synchronized long rewardsRetryAtMs() { return state.optLong("rewardsRetryAtMs", 0); }
    synchronized void delayRewardsUntil(long until) throws Exception {
        if (until <= rewardsRetryAtMs()) return;
        JSONObject candidate = new JSONObject(state.toString()).put("rewardsRetryAtMs", until);
        save(settings, candidate.toString().getBytes(StandardCharsets.UTF_8)); state = candidate;
    }
    /** An SSH credential denial applies to every target sharing that same credential profile. */
    synchronized boolean suspendCredentialProfile(JSONObject expected) throws Exception {
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return false;
        boolean authorizedReceipt = false;
        for (int i = 0; i < targets.length(); i++) if (sameAuthorization(targets.optJSONObject(i), expected)) authorizedReceipt = true;
        if (!authorizedReceipt) return false;
        boolean changed = false;
        String credential = expected.getString("credentialId");
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (current == null || !credential.equals(current.optString("credentialId"))) continue;
            changed |= !current.optBoolean("paused") || !"credentials-required".equals(current.optString("status"));
            current.put("paused", true).put("status", "credentials-required");
        }
        if (changed) saveCredentials(candidate);
        return true;
    }
    /** Revoke the entire selected fleet atomically, independently of node-state validity. */
    synchronized JSONArray pauseMonitoringFleet(String fleetId) throws Exception {
        if (fleetId == null || !fleetId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("Known fleet required");
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets"), paused = new JSONArray();
        if (targets == null) return paused;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (current == null || !fleetId.equals(current.optString("fleetId"))) continue;
            current.put("approved", false).put("paused", true).put("status", "paused");
            paused.put(new JSONObject(current.toString()));
        }
        if (paused.length() > 0) saveCredentials(candidate);
        return paused;
    }
    /** Merge restrictions into the currently saved authorization; never restore a forgotten target. */
    synchronized void restrictMonitoring(JSONObject expected, String address, boolean suspendRewards,
            boolean pause, String status, long retryAt) throws Exception {
        if (retryAt > 0) delayRewardsUntil(retryAt);
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (!sameAuthorization(current, expected)) continue;
            JSONArray suspended = current.optJSONArray("rewardsSuspended");
            if (suspended == null) suspended = new JSONArray();
            if (suspendRewards) {
                boolean found = false; for (int j = 0; j < suspended.length(); j++) if (address.equals(suspended.getString(j))) found = true;
                if (!found) suspended.put(address);
            }
            current.put("rewardsSuspended", suspended);
            JSONArray verified = current.optJSONArray("rewardsVerified");
            if (verified != null && suspendRewards) for (int j = verified.length() - 1; j >= 0; j--) if (address.equals(verified.getString(j))) verified.remove(j);
            JSONArray bindings = current.optJSONArray("bindings");
            current.put("rewardsAuthorized", current.optBoolean("rewardsAuthorized") && current.optBoolean("approved")
                    && bindings != null && suspended.length() < bindings.length());
            if (pause) current.put("paused", true);
            // A source-specific denial must not replace a connection/manual/node-binding pause.
            if (pause || suspendRewards && !current.optBoolean("paused")) current.put("status", status);
            if (retryAt > 0) current.put("rewardsRetryAtMs", Math.max(current.optLong("rewardsRetryAtMs"), retryAt));
            saveCredentials(candidate); return;
        }
    }
    /** PC trust failure pauses connections without changing source permissions or history. */
    synchronized void restrictPcConnection(JSONObject expected, String status) throws Exception {
        if (!"identity-changed".equals(status)) throw new IllegalArgumentException("PC identity restriction required");
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (!sameAuthorization(current, expected)) continue;
            current.put("paused", true).put("status", status);
            saveCredentials(candidate); return;
        }
    }
    /** IP scheduler state changes no node/rewards/GPU or authorization fields. */
    synchronized void recordNetworkAttempt(JSONObject expected, long at) throws Exception {
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (!sameAuthorization(current, expected)) continue;
            current.put("lastNetworkAttemptAtMs", at).put("networkRetryAtMs", at + PublicNetworkCache.FAILURE_RETRY_MS);
            saveCredentials(candidate); return;
        }
    }
    private static boolean sameAuthorization(JSONObject current, JSONObject expected) {
        if (current == null || expected == null) return false;
        try {
            return current.getString("id").equals(expected.getString("id"))
                    && current.optString("authorizationId").equals(expected.optString("authorizationId"))
                    && current.getString("credentialId").equals(expected.getString("credentialId"))
                    && current.getString("keyBase64").equals(expected.getString("keyBase64"));
        } catch (Exception invalidRecord) { return false; }
    }
    /** A completed worker cannot replace a concurrent revocation or IP-lane restriction. */
    synchronized void saveMonitoringProgress(JSONObject target) throws Exception {
        JSONArray targets = monitoringTargets();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (!sameAuthorization(current, target)) continue;
            if (!current.optBoolean("approved") || current.optBoolean("aliasReplaced")) return;
            JSONObject copy = new JSONObject(target.toString());
            for (String field : new String[]{"lastNetworkAttemptAtMs", "networkRetryAtMs"})
                if (current.has(field)) copy.put(field, Math.max(copy.optLong(field), current.optLong(field)));
            if (current.optBoolean("paused") && ("identity-changed".equals(current.optString("status"))
                    || "credentials-required".equals(current.optString("status")) || "paused".equals(current.optString("status"))))
                copy.put("paused", true).put("status", current.getString("status"));
            saveMonitoringTarget(copy); return;
        }
    }
    synchronized JSONObject findMonitoringTarget(String id) throws Exception {
        JSONArray targets = monitoringTargets();
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target != null && id.equals(target.optString("id"))) return new JSONObject(target.toString());
        }
        return null;
    }
    synchronized JSONObject findMonitoringPc(String fleet, String pc) throws Exception {
        JSONArray targets = monitoringTargets(); JSONObject selected = null;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target == null || !fleet.equals(target.optString("fleetId")) || !pc.equals(target.optString("pcId"))) continue;
            if (selected == null || selected.optBoolean("aliasReplaced") && !target.optBoolean("aliasReplaced")
                    || !target.optBoolean("aliasReplaced") && !selected.optBoolean("aliasReplaced") && target.optBoolean("approved") && !selected.optBoolean("approved")) selected = target;
        }
        return selected == null ? null : new JSONObject(selected.toString());
    }
    /** A direct native check can commit only the unchanged consent snapshot it verified. */
    synchronized boolean approveDirectPcIp(JSONObject expected, JSONObject replacement, String credentialSnapshot,
            java.util.function.BooleanSupplier consentActive) throws Exception {
        if (!consentActive.getAsBoolean()) return false;
        JSONObject credential = credential(replacement.getString("credentialId"));
        if (credential == null || !credentialSnapshot.equals(credential.toString())) return false;
        if (expected == null) {
            if (findMonitoringPc(replacement.getString("fleetId"), replacement.getString("pcId")) != null) return false;
        } else {
            JSONObject current = findMonitoringTarget(expected.getString("id"));
            if (current == null || !current.toString().equals(expected.toString())) return false;
        }
        String endpoint = new DiscoveryPolicy.Candidate(replacement.getString("address"), replacement.getInt("port"), "").endpointKey();
        if (!replacement.getString("keyBase64").equals(trustedKey(replacement.getString("profileId"), endpoint)) || !consentActive.getAsBoolean()) return false;
        savePcMonitoringTarget(replacement); return true;
    }
    /** A real rejection of explicitly selected saved credentials pauses that profile everywhere. */
    synchronized void suspendDirectPcCredential(String credentialId, String expectedCredential) throws Exception {
        JSONObject currentCredential = credential(credentialId);
        if (currentCredential == null || !currentCredential.toString().equals(expectedCredential)) return;
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        boolean changed = false;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.optJSONObject(i);
            if (target != null && credentialId.equals(target.optString("credentialId"))) {
                target.put("paused", true).put("status", "credentials-required"); changed = true;
            }
        }
        if (changed) saveCredentials(candidate);
    }
    /** Explicit PC-only approval updates connection authority while retaining opaque node policy/history. */
    synchronized void savePcMonitoringTarget(JSONObject target) throws Exception {
        MonitoringController.validatePcTarget(target);
        if (!target.optBoolean("approved") || !target.optBoolean("paused") || !"binding-changed".equals(target.optString("status"))
                || credential(target.getString("credentialId")) == null)
            throw new java.security.GeneralSecurityException("PC-only approval requires a remembered credential and paused node work");
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) { targets = new JSONArray(); candidate.put("monitoringTargets", targets); }
        int existing = -1; JSONObject copy = null;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.optJSONObject(i);
            if (current == null || !target.getString("id").equals(current.optString("id"))) continue;
            if (!target.getString("fleetId").equals(current.optString("fleetId")) || !target.getString("pcId").equals(current.optString("pcId")))
                throw new java.security.GeneralSecurityException("PC approval cannot change saved ownership");
            existing = i; copy = new JSONObject(current.toString()); break;
        }
        if (copy == null) {
            MonitoringController.validateTarget(target);
            if (target.getJSONArray("bindings").length() != 0 || target.optBoolean("rewardsAuthorized"))
                throw new java.security.GeneralSecurityException("PC-only approval cannot grant node permissions");
            copy = new JSONObject(target.toString());
        } else {
            for (String field : new String[]{"profileId", "address", "port", "keyBase64", "algorithm", "pcId", "credentialId", "approved", "paused", "status", "authorizationId", "setupPending"})
                copy.put(field, target.get(field));
        }
        copy.remove("aliasReplaced");
        for (int i = 0; i < targets.length(); i++) {
            JSONObject prior = targets.optJSONObject(i);
            if (prior == null || i == existing || !prior.optBoolean("approved")
                    || !copy.getString("fleetId").equals(prior.optString("fleetId")) || !copy.getString("pcId").equals(prior.optString("pcId"))) continue;
            for (String field : new String[]{"retryAtMs", "rewardsRetryAtMs", "lastGpuAttemptAtMs", "lastRewardsAttemptAtMs", "lastNetworkAttemptAtMs", "networkRetryAtMs"})
                if (prior.has(field)) copy.put(field, Math.max(copy.optLong(field), prior.optLong(field)));
            // Carry every known wallet denial; PC-only repair cannot grant or verify a wallet read.
            java.util.LinkedHashSet<String> denied = new java.util.LinkedHashSet<>();
            for (JSONObject record : new JSONObject[]{copy, prior}) {
                JSONArray values = record.optJSONArray("rewardsSuspended");
                if (record.has("rewardsSuspended") && values == null) throw new java.security.GeneralSecurityException("Saved source policy needs recovery");
                if (values != null) for (int j = 0; j < values.length(); j++) denied.add(values.getString(j));
            }
            copy.put("rewardsSuspended", new JSONArray(denied));
            prior.put("approved", false).put("paused", true).put("status", "paused").put("aliasReplaced", true);
        }
        if (existing >= 0) targets.put(existing, copy);
        else { if (targets.length() >= 512) throw new IllegalArgumentException("At most 512 monitored PCs can be saved"); targets.put(copy); }
        saveCredentials(candidate);
    }
    synchronized void saveMonitoringTarget(JSONObject target) throws Exception {
        MonitoringController.validateTarget(target);
        if (target.optBoolean("approved") && credential(target.getString("credentialId")) == null)
            throw new java.security.GeneralSecurityException("Monitoring needs an existing remembered credential");
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) { targets = new JSONArray(); candidate.put("monitoringTargets", targets); }
        JSONObject copy = new JSONObject(target.toString());
        int existing = -1;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject prior = targets.optJSONObject(i);
            if (prior == null) continue;
            if (prior.optString("id").equals(target.getString("id"))) {
                existing = i;
                if (copy.optBoolean("approved") && prior.optBoolean("aliasReplaced")
                        && prior.optString("authorizationId").equals(copy.optString("authorizationId")))
                    throw new SecurityException("This monitoring endpoint was replaced");
                continue;
            }
            if (copy.optBoolean("approved") && prior.optBoolean("approved")
                    && copy.getString("fleetId").equals(prior.optString("fleetId")) && copy.getString("pcId").equals(prior.optString("pcId"))) {
                for (String field : new String[]{"retryAtMs", "rewardsRetryAtMs", "lastGpuAttemptAtMs", "lastRewardsAttemptAtMs"})
                    copy.put(field, Math.max(copy.optLong(field), prior.optLong(field)));
                java.util.Set<String> members = new java.util.HashSet<>(), suspended = new java.util.LinkedHashSet<>();
                JSONArray bindings = copy.getJSONArray("bindings");
                for (int j = 0; j < bindings.length(); j++) members.add(bindings.getJSONObject(j).getString("address"));
                for (JSONObject source : new JSONObject[]{copy, prior}) {
                    JSONArray values = source.optJSONArray("rewardsSuspended");
                    if (values != null) for (int j = 0; j < values.length(); j++) if (members.contains(values.getString(j))) suspended.add(values.getString(j));
                }
                copy.put("rewardsSuspended", new JSONArray(suspended)).put("rewardsAuthorized", suspended.size() < members.size());
                if (copy.getJSONArray("bindings").toString().equals(String.valueOf(prior.optJSONArray("bindings")))) {
                    JSONObject cursors = copy.optJSONObject("gpuCursors"); if (cursors == null) cursors = new JSONObject();
                    JSONObject previous = prior.optJSONObject("gpuCursors");
                    if (previous != null) for (String address : members) {
                        long cursor = Math.max(cursors.optLong(address), previous.optLong(address)); if (cursor > 0) cursors.put(address, cursor);
                    }
                    copy.put("gpuCursors", cursors);
                }
                prior.put("approved", false).put("paused", true).put("status", "paused").put("aliasReplaced", true);
            }
        }
        if (copy.optBoolean("approved")) copy.remove("aliasReplaced");
        if (existing >= 0) targets.put(existing, copy);
        else {
            if (targets.length() >= 512) throw new IllegalArgumentException("At most 512 monitored PCs can be saved");
            targets.put(copy);
        }
        saveCredentials(candidate);
    }
    synchronized void removeMonitoringFleet(String fleetId) throws Exception {
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        for (int i = targets.length() - 1; i >= 0; i--) if (fleetId.equals(targets.getJSONObject(i).getString("fleetId"))) targets.remove(i);
        saveCredentials(candidate);
    }
    private void saveCredentials(JSONObject candidate) throws Exception {
        byte[] clear = candidate.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (clear.length > 2 * 1024 * 1024 - 29) throw new java.io.IOException("Credential store is full");
            save(secrets, CredentialCipher.encrypt(key(true), clear)); credentials = candidate;
        }
        finally { Arrays.fill(clear, (byte) 0); }
    }
    synchronized void forgetCredentials() throws Exception {
        // Destroying the key first makes even an interrupted file deletion fail closed.
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias);
        secrets.delete(); credentials = null; credentialFailure = false;
    }
    synchronized void lock() { credentials = null; }
    private void save(AtomicFile file, byte[] bytes) throws Exception {
        if (bytes.length > (file == settings ? 1024 * 1024 : 2 * 1024 * 1024)) throw new java.io.IOException("Discovery storage is full");
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); file.finishWrite(output); }
        catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
    }
}
