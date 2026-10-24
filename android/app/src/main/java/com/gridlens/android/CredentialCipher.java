package com.gridlens.android;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned authenticated encryption. Keys are supplied by Android Keystore in production. */
final class CredentialCipher {
    private static final byte VERSION = 1;
    private static final byte[] CONTEXT = "GridLens Android discovery credentials v1".getBytes(StandardCharsets.UTF_8);
    static byte[] encrypt(SecretKey key, byte[] plaintext) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(CONTEXT);
        byte[] iv = cipher.getIV();
        if (iv.length != 12) throw new GeneralSecurityException("Unexpected encryption nonce");
        byte[] ciphertext = cipher.doFinal(plaintext);
        return ByteBuffer.allocate(1 + iv.length + ciphertext.length).put(VERSION).put(iv).put(ciphertext).array();
    }
    static byte[] decrypt(SecretKey key, byte[] envelope) throws GeneralSecurityException {
        if (envelope.length < 30 || envelope.length > 2 * 1024 * 1024 || envelope[0] != VERSION)
            throw new GeneralSecurityException("Invalid encrypted record");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
        cipher.updateAAD(CONTEXT);
        return cipher.doFinal(envelope, 13, envelope.length - 13);
    }
    private CredentialCipher() {}
}
