package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public final class PcIpEnrollmentTest {
    @Test public void authenticatedPcProofDoesNotDependOnContainerBindingsOrInventorySuccess() throws Exception {
        SshDiscovery.configureCryptoForAndroid();
        com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        com.jcraft.jsch.KeyPair other = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        try (SshDiscovery.Credential credential = credential("fixture", "disposable")) {
            SshDiscovery.Identity identity = identity(pair), different = identity(other);
            DiscoveryPolicy.Candidate endpoint = new DiscoveryPolicy.Candidate("192.0.2.62", 2201, "");
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(endpoint, identity, credential, null);
            PcMetadata metadata = PcMetadata.observed(identity, endpoint.address, "arbitrary-pc-name", endpoint.address, 1000);
            for (SshDiscovery.Status status : SshDiscovery.Status.values()) {
                SshDiscovery.Result result = new SshDiscovery.Result(status, Collections.emptyList(), 3, metadata, null, Collections.emptyList());
                boolean valid = status == SshDiscovery.Status.SUCCESS || status == SshDiscovery.Status.INVENTORY_FAILED
                        || status == SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED;
                assertEquals(status.name(), valid, PcIpEnrollment.authenticatedPc(request, result));
            }
            assertFalse(PcIpEnrollment.authenticatedPc(request, null));
            assertFalse(PcIpEnrollment.authenticatedPc(request, new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, Collections.emptyList(), 3)));
            assertFalse(PcIpEnrollment.authenticatedPc(request, new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, Collections.emptyList(), 3,
                    PcMetadata.observed(different, endpoint.address, "arbitrary-pc-name", endpoint.address, 1000))));
        } finally { pair.dispose(); other.dispose(); }
    }

    @Test public void rememberedCredentialMustMatchWhatActuallyAuthenticated() {
        try (SshDiscovery.Credential authenticated = credential("fixture", "disposable");
             SshDiscovery.Credential identical = credential("fixture", "disposable");
             SshDiscovery.Credential replaced = credential("fixture", "replacement");
             SshDiscovery.Credential otherUser = credential("other", "disposable");
             SshDiscovery.Credential normalized = new SshDiscovery.Credential("fixture", bytes("disposable"), new byte[0], new byte[0])) {
            assertTrue(PcIpEnrollment.sameCredential(authenticated, identical));
            assertTrue(PcIpEnrollment.sameCredential(authenticated, normalized));
            assertFalse(PcIpEnrollment.sameCredential(authenticated, replaced));
            assertFalse(PcIpEnrollment.sameCredential(authenticated, otherUser));
            authenticated.close();
            assertFalse(PcIpEnrollment.sameCredential(authenticated, identical));
        }
    }

    @Test public void importedSshKeysAndPassphrasesMustMatchIndependently() {
        try (SshDiscovery.Credential authenticated = new SshDiscovery.Credential("fixture", null, bytes("disposable-key"), bytes("passphrase"));
             SshDiscovery.Credential identical = new SshDiscovery.Credential("fixture", null, bytes("disposable-key"), bytes("passphrase"));
             SshDiscovery.Credential replacedKey = new SshDiscovery.Credential("fixture", null, bytes("different-key"), bytes("passphrase"));
             SshDiscovery.Credential replacedPhrase = new SshDiscovery.Credential("fixture", null, bytes("disposable-key"), bytes("different"))) {
            assertTrue(PcIpEnrollment.sameCredential(authenticated, identical));
            assertFalse(PcIpEnrollment.sameCredential(authenticated, replacedKey));
            assertFalse(PcIpEnrollment.sameCredential(authenticated, replacedPhrase));
        }
    }

    @Test public void onlyUnusedUnapprovedInspectionCanBecomePcOnlyAccess() {
        assertTrue(PcIpEnrollment.unusedInspection(false, false, "authorization-required", false, false, true));
        assertFalse(PcIpEnrollment.unusedInspection(true, false, "authorization-required", false, false, true));
        assertFalse(PcIpEnrollment.unusedInspection(false, true, "authorization-required", false, false, true));
        assertFalse(PcIpEnrollment.unusedInspection(false, false, "authorization-required", true, false, true));
        assertFalse(PcIpEnrollment.unusedInspection(false, false, "authorization-required", false, true, true));
        assertFalse(PcIpEnrollment.unusedInspection(false, false, "authorization-required", false, false, false));
        for (String status : new String[]{"active", "paused", "credentials-required", "identity-changed", "binding-changed", "", null})
            assertFalse(PcIpEnrollment.unusedInspection(false, false, status, false, false, true));
    }

    private static SshDiscovery.Identity identity(com.jcraft.jsch.KeyPair pair) {
        return new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(pair.getPublicKeyBlob()), "ssh-rsa");
    }
    private static SshDiscovery.Credential credential(String username, String password) {
        return new SshDiscovery.Credential(username, bytes(password), null, null);
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
