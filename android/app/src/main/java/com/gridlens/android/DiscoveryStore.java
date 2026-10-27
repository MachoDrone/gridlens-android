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
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.getJSONObject(i);
            if (current.getString("id").equals(expected.getString("id"))
                    && current.optString("authorizationId").equals(expected.optString("authorizationId"))
                    && current.getString("credentialId").equals(expected.getString("credentialId"))
                    && current.getString("keyBase64").equals(expected.getString("keyBase64"))) authorizedReceipt = true;
        }
        if (!authorizedReceipt) return false;
        boolean changed = false;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.getJSONObject(i);
            if (!current.getString("credentialId").equals(expected.getString("credentialId"))) continue;
            changed |= !current.optBoolean("paused") || !"credentials-required".equals(current.optString("status"));
            current.put("paused", true).put("status", "credentials-required");
        }
        if (changed) saveCredentials(candidate);
        return true;
    }
    /** Merge restrictions into the currently saved authorization; never restore a forgotten target. */
    synchronized void restrictMonitoring(JSONObject expected, String address, boolean suspendRewards,
            boolean pause, String status, long retryAt) throws Exception {
        if (retryAt > 0) delayRewardsUntil(retryAt);
        JSONObject candidate = new JSONObject(credentials().toString());
        JSONArray targets = candidate.optJSONArray("monitoringTargets");
        if (targets == null) return;
        for (int i = 0; i < targets.length(); i++) {
            JSONObject current = targets.getJSONObject(i);
            if (!current.getString("id").equals(expected.getString("id"))
                    || !current.optString("authorizationId").equals(expected.optString("authorizationId"))
                    || !current.getString("credentialId").equals(expected.getString("credentialId"))
                    || !current.getString("keyBase64").equals(expected.getString("keyBase64"))) continue;
            JSONArray suspended = current.optJSONArray("rewardsSuspended");
            if (suspended == null) suspended = new JSONArray();
            if (suspendRewards) {
                boolean found = false; for (int j = 0; j < suspended.length(); j++) if (address.equals(suspended.getString(j))) found = true;
                if (!found) suspended.put(address);
            }
            current.put("rewardsSuspended", suspended);
            JSONArray verified = current.optJSONArray("rewardsVerified");
            if (verified != null && suspendRewards) for (int j = verified.length() - 1; j >= 0; j--) if (address.equals(verified.getString(j))) verified.remove(j);
            current.put("rewardsAuthorized", current.optBoolean("approved") && suspended.length() < current.getJSONArray("bindings").length());
            if (pause) current.put("paused", true);
            if (pause || suspendRewards) current.put("status", status);
            if (retryAt > 0) current.put("rewardsRetryAtMs", Math.max(current.optLong("rewardsRetryAtMs"), retryAt));
            saveCredentials(candidate); return;
        }
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
            JSONObject prior = targets.getJSONObject(i);
            if (prior.getString("id").equals(target.getString("id"))) {
                existing = i;
                if (copy.optBoolean("approved") && prior.optBoolean("aliasReplaced")
                        && prior.optString("authorizationId").equals(copy.optString("authorizationId")))
                    throw new SecurityException("This monitoring endpoint was replaced");
                continue;
            }
            if (copy.optBoolean("approved") && prior.optBoolean("approved")
                    && copy.getString("fleetId").equals(prior.getString("fleetId")) && copy.getString("pcId").equals(prior.getString("pcId"))) {
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
                if (copy.getJSONArray("bindings").toString().equals(prior.getJSONArray("bindings").toString())) {
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
