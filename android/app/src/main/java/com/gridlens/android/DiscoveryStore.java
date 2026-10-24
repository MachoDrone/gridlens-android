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
