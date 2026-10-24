package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public class CredentialCipherTest {
    private SecretKey key() throws Exception { KeyGenerator generator = KeyGenerator.getInstance("AES"); generator.init(256); return generator.generateKey(); }
    @Test public void encryptedPersistenceUsesAuthenticatedFreshNonces() throws Exception {
        SecretKey key = key(); byte[] clear = "fixture SSH credential".getBytes(StandardCharsets.UTF_8);
        byte[] one = CredentialCipher.encrypt(key, clear), two = CredentialCipher.encrypt(key, clear);
        assertFalse(Arrays.equals(one, two)); assertArrayEquals(clear, CredentialCipher.decrypt(key, one));
        assertFalse(new String(one, StandardCharsets.UTF_8).contains("fixture SSH credential"));
        one[one.length - 1] ^= 1;
        try { CredentialCipher.decrypt(key, one); fail("Tampering must fail"); } catch (GeneralSecurityException expected) {}
    }
    @Test public void invalidatedKeyAndUnsupportedFormatFailClosed() throws Exception {
        byte[] ciphertext = CredentialCipher.encrypt(key(), new byte[]{1,2,3});
        try { CredentialCipher.decrypt(key(), ciphertext); fail("A replacement key must not recover secrets"); } catch (GeneralSecurityException expected) {}
        ciphertext[0] = 9;
        try { CredentialCipher.decrypt(key(), ciphertext); fail("Unsupported format must fail"); } catch (GeneralSecurityException expected) {}
    }
}
