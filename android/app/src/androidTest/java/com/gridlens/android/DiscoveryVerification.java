package com.gridlens.android;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;

/** Isolated app-private fixture; never touches production discovery records. */
final class DiscoveryVerification {
    static void verify(Context context) throws Exception {
        verifySshKeyFormats();
        String namespace = "discovery-verification";
        File directory = new File(context.getNoBackupFilesDir(), namespace);
        require(!directory.exists(), "Discovery instrumentation requires an unused fixture namespace");
        String alias = "gridlens.discovery.credentials.v1." + namespace;
        DiscoveryStore store = new DiscoveryStore(context, namespace);
        try {
            String profile = store.saveProfile(null, "Synthetic network", new JSONArray().put("192.0.2.0/24"), new JSONArray().put(22));
            store.trust(profile, "[192.0.2.12]:22", "synthetic-host-key");
            String password = "isolated-test-password";
            String id = store.saveCredential(new JSONObject().put("name", "Fixture credential").put("username", "fixture")
                .put("password", password).put("key", "").put("passphrase", ""), "synthetic-host-key");
            byte[] encrypted = Files.readAllBytes(new File(directory, "credentials.aesgcm").toPath());
            require(!new String(encrypted, StandardCharsets.UTF_8).contains(password), "Credential plaintext persisted");
            DiscoveryStore reopened = new DiscoveryStore(context, namespace);
            require(password.equals(reopened.credential(id).getString("password")), "Keystore credential did not survive reopening");
            require(id.equals(reopened.override("synthetic-host-key")), "Credential override did not survive reopening");
            require(reopened.profiles().length() == 1, "Network profile did not survive reopening");
            try { reopened.trust(profile, "[192.0.2.12]:22", "changed-key"); throw new AssertionError("Changed host key accepted"); }
            catch (SecurityException expected) { }
            reopened.forgetCredentials();
            require(reopened.credentialProfiles().length() == 0, "Forget left credentials available");
            require(!new File(directory, "credentials.aesgcm").exists(), "Forget left encrypted file");
            require(reopened.profiles().length() == 1 && "synthetic-host-key".equals(reopened.trustedKey(profile, "[192.0.2.12]:22")), "Forget removed profile or trust");
            reopened.saveCredential(new JSONObject().put("name", "Reentered").put("username", "fixture")
                .put("password", password).put("key", "").put("passphrase", ""), null);
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null); keys.deleteEntry(alias);
            try {
                reopened.saveCredential(new JSONObject().put("name", "After invalidation").put("username", "fixture")
                    .put("password", password).put("key", "").put("passphrase", ""), null);
                throw new AssertionError("Cached credentials silently replaced an invalidated key");
            } catch (java.security.GeneralSecurityException expected) { }
            DiscoveryStore invalidated = new DiscoveryStore(context, namespace);
            try { invalidated.credentialProfiles(); throw new AssertionError("Invalidated key silently replaced"); }
            catch (java.security.GeneralSecurityException expected) { }
            require(new File(directory, "credentials.aesgcm").exists(), "Invalidated ciphertext was discarded");
            invalidated.forgetCredentials();
            require(invalidated.credentialProfiles().length() == 0, "Could not recover after explicit forget");
            Arrays.fill(encrypted, (byte) 0);
        } finally {
            store.forgetCredentials();
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) require(file.delete(), "Fixture cleanup failed");
            require(directory.delete(), "Fixture directory cleanup failed");
        }
    }
    private static void verifySshKeyFormats() throws Exception {
        SshDiscovery.configureCryptoForAndroid();
        for (int type : new int[]{com.jcraft.jsch.KeyPair.RSA, com.jcraft.jsch.KeyPair.ECDSA, com.jcraft.jsch.KeyPair.ED25519}) {
            com.jcraft.jsch.JSch client = new com.jcraft.jsch.JSch();
            com.jcraft.jsch.KeyPair key = com.jcraft.jsch.KeyPair.genKeyPair(client, type,
                type == com.jcraft.jsch.KeyPair.RSA ? 2048 : 256);
            try {
                for (boolean encrypted : new boolean[]{false, true}) {
                    byte[] passphrase = encrypted ? "isolated-key-passphrase".getBytes(StandardCharsets.UTF_8) : null;
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                    if (type == com.jcraft.jsch.KeyPair.ED25519) key.writeOpenSSHv1PrivateKey(output, passphrase);
                    else key.writePrivateKey(output, passphrase);
                    byte[] encoded = output.toByteArray();
                    com.jcraft.jsch.KeyPair imported = com.jcraft.jsch.KeyPair.load(client, encoded, null);
                    try {
                        if (encrypted) require(imported.isEncrypted() && imported.decrypt(passphrase), "Encrypted SSH key failed on Android");
                        require(!imported.isEncrypted(), "SSH key remained encrypted");
                        require(Arrays.equals(key.getPublicKeyBlob(), imported.getPublicKeyBlob()), "SSH key changed on import");
                        byte[] challenge = "isolated-ssh-compatibility-check".getBytes(StandardCharsets.UTF_8);
                        String algorithm = type == com.jcraft.jsch.KeyPair.RSA ? "rsa-sha2-512" : imported.getKeyTypeString();
                        byte[] signature = imported.getSignature(challenge, algorithm);
                        com.jcraft.jsch.Signature verifier = key.getVerifier(algorithm);
                        require(signature != null && verifier != null, "SSH signature implementation unavailable");
                        verifier.update(challenge);
                        require(verifier.verify(signature), "SSH signature verification failed on Android");
                        client.addIdentity("isolated", encoded, null, passphrase);
                        client.removeAllIdentity();
                    } finally { imported.dispose(); Arrays.fill(encoded, (byte) 0); if (passphrase != null) Arrays.fill(passphrase, (byte) 0); }
                }
            } finally { key.dispose(); }
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private DiscoveryVerification() { }
}
