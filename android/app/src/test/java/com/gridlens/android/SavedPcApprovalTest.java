package com.gridlens.android;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SavedPcApprovalTest {
    @Test public void onlyExplicitSavedFleetApprovalCanContinueAfterVerifiedPcButMissingNodeBindings() throws Exception {
        SshDiscovery.configureCryptoForAndroid();
        com.jcraft.jsch.KeyPair pair = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        com.jcraft.jsch.KeyPair other = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
        try (SshDiscovery.Credential credential = new SshDiscovery.Credential("fixture", "fixture-password".getBytes(java.nio.charset.StandardCharsets.UTF_8), null, null)) {
            SshDiscovery.Identity identity = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(pair.getPublicKeyBlob()), "ssh-rsa");
            SshDiscovery.Identity changed = new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(other.getPublicKeyBlob()), "ssh-rsa");
            DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate("192.0.2.40", 22, "");
            SshDiscovery.BatchRequest request = new SshDiscovery.BatchRequest(candidate, identity, credential, null);
            PcMetadata pc = PcMetadata.observed(identity, candidate.address, "fixture-pc", candidate.address, 1000);
            String fleet = UUID.randomUUID().toString();
            SshDiscovery.Result verified = new SshDiscovery.Result(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED,
                    Collections.emptyList(), 3, pc, null, Collections.emptyList());
            Map<String, SshDiscovery.Result> result = Map.of(candidate.endpointKey(), verified);
            assertTrue(DiscoveryController.allowsEmptyMonitoringMerge(fleet, true, List.of(request), result));
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(fleet, false, List.of(request), result));
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(null, true, List.of(request), result));
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge("invalid", true, List.of(request), result));
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(fleet, true, List.of(request), Collections.emptyMap()));
            for (SshDiscovery.Status status : new SshDiscovery.Status[]{SshDiscovery.Status.AUTH_FAILED, SshDiscovery.Status.IDENTITY_CHANGED,
                    SshDiscovery.Status.CANCELLED, SshDiscovery.Status.UNREACHABLE}) {
                SshDiscovery.Result denied = new SshDiscovery.Result(status, Collections.emptyList(), 3, pc, null, Collections.emptyList());
                assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(fleet, true, List.of(request), Map.of(candidate.endpointKey(), denied)));
            }
            SshDiscovery.Result wrongPc = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, Collections.emptyList(), 3,
                    PcMetadata.observed(changed, candidate.address, "other-pc", candidate.address, 1000), null, Collections.emptyList());
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(fleet, true, List.of(request), Map.of(candidate.endpointKey(), wrongPc)));
            SshDiscovery.Result noProof = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, Collections.emptyList(), 3);
            assertFalse(DiscoveryController.allowsEmptyMonitoringMerge(fleet, true, List.of(request), Map.of(candidate.endpointKey(), noProof)));
        } finally { pair.dispose(); other.dispose(); }
    }
}
