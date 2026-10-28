package com.gridlens.android;

import org.junit.BeforeClass;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class InspectionSelectionTest {
    private static SshDiscovery.Identity firstIdentity, secondIdentity;
    private static final String FIRST = "11111111111111111111111111111111";
    private static final String SECOND = "So11111111111111111111111111111111111111112";
    private static final String THIRD = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final DiscoveryPolicy.Candidate FIRST_PC = new DiscoveryPolicy.Candidate("192.0.2.10", 22, "first");
    private static final DiscoveryPolicy.Candidate SECOND_PC = new DiscoveryPolicy.Candidate("192.0.2.11", 22, "second");

    @BeforeClass public static void generatedIdentities() {
        firstIdentity = identity(); secondIdentity = identity();
    }

    @Test public void oneOfThreeHostsKeepsOnlyItsMetadataAndBinding() {
        SshDiscovery.Result all = result(firstIdentity, FIRST_PC, List.of(FIRST, SECOND, THIRD));
        InspectionSelection selected = InspectionSelection.from(Map.of(FIRST_PC.endpointKey(), all), List.of(SECOND));
        assertEquals(List.of(SECOND), selected.addresses);
        assertEquals(Collections.singleton(SECOND), selected.metadata.keySet());
        assertEquals(List.of(SECOND), selected.resultFor(FIRST_PC.endpointKey()).addresses);
        assertEquals(1, selected.resultFor(FIRST_PC.endpointKey()).bindings.size());
        assertEquals(SECOND, selected.resultFor(FIRST_PC.endpointKey()).bindings.get(0).address);
        assertTrue(selected.includesEndpointPc(FIRST_PC.endpointKey(), all.pcMetadata.pcId));
        assertFalse(selected.includesEveryBinding(all.pcMetadata.pcId, all.bindings));
        assertTrue(InspectionSelection.from(Map.of(FIRST_PC.endpointKey(), all), all.addresses)
                .includesEveryBinding(all.pcMetadata.pcId, all.bindings));
        assertEquals(3, selected.completeResults.get(FIRST_PC.endpointKey()).bindings.size());
    }

    @Test public void conflictsAreComputedBeforeFilteringAndCannotCreatePcAuthority() {
        SshDiscovery.Result first = result(firstIdentity, FIRST_PC, List.of(FIRST, SECOND));
        SshDiscovery.Result second = result(secondIdentity, SECOND_PC, List.of(FIRST));
        Map<String, SshDiscovery.Result> results = Map.of(FIRST_PC.endpointKey(), first, SECOND_PC.endpointKey(), second);
        InspectionSelection selected = InspectionSelection.from(results, List.of(FIRST));
        assertNull(selected.metadata.get(FIRST).pcId);
        assertTrue(selected.pcIds.isEmpty());
        assertFalse(selected.includesEndpointPc(FIRST_PC.endpointKey(), first.pcMetadata.pcId));
        assertFalse(selected.includesEndpointPc(SECOND_PC.endpointKey(), second.pcMetadata.pcId));
        assertTrue(selected.resultFor(FIRST_PC.endpointKey()).bindings.isEmpty());
        assertTrue(selected.resultFor(SECOND_PC.endpointKey()).bindings.isEmpty());

        InspectionSelection mixed = InspectionSelection.from(results, List.of(FIRST, SECOND));
        assertTrue(mixed.includesEndpointPc(FIRST_PC.endpointKey(), first.pcMetadata.pcId));
        assertFalse(mixed.includesEndpointPc(SECOND_PC.endpointKey(), second.pcMetadata.pcId));
        assertEquals(SECOND, mixed.resultFor(FIRST_PC.endpointKey()).bindings.get(0).address);
        assertEquals(1, mixed.resultFor(FIRST_PC.endpointKey()).bindings.size());
    }

    @Test public void competingBindingsOnTheSamePcRemainUnresolved() {
        SshDiscovery.Result first = result(firstIdentity, FIRST_PC, List.of(FIRST));
        DiscoveryPolicy.Candidate alias = new DiscoveryPolicy.Candidate("192.0.2.12", 22, "alias");
        SshDiscovery.Result second = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of(FIRST), 0,
                PcMetadata.observed(firstIdentity, alias.address, "first", alias.address, 1000), null,
                List.of(new NodeBinding("docker", null, "f".repeat(64), FIRST)));
        InspectionSelection selected = InspectionSelection.from(Map.of(FIRST_PC.endpointKey(), first, alias.endpointKey(), second), List.of(FIRST));
        assertTrue(selected.includesPc(first.pcMetadata.pcId));
        assertTrue(selected.resultFor(FIRST_PC.endpointKey()).bindings.isEmpty());
        assertTrue(selected.resultFor(alias.endpointKey()).bindings.isEmpty());
    }

    @Test public void excludingFailedPcKeepsSharedCredentialRejectionEvidence() {
        try (SshDiscovery.Credential shared = credential("same"); SshDiscovery.Credential other = credential("different")) {
            SshDiscovery.BatchRequest selectedRequest = new SshDiscovery.BatchRequest(FIRST_PC, firstIdentity, shared, null);
            SshDiscovery.BatchRequest rejectedRequest = new SshDiscovery.BatchRequest(SECOND_PC, secondIdentity, shared, null);
            SshDiscovery.BatchRequest otherRequest = new SshDiscovery.BatchRequest(FIRST_PC, firstIdentity, other, null);
            Map<String, SshDiscovery.Result> results = Map.of(FIRST_PC.endpointKey(), result(firstIdentity, FIRST_PC, List.of(FIRST)),
                    SECOND_PC.endpointKey(), SshDiscovery.Result.of(SshDiscovery.Status.AUTH_FAILED));
            InspectionSelection selected = InspectionSelection.from(results, List.of(FIRST));
            assertNull(selected.resultFor(SECOND_PC.endpointKey()));
            assertEquals(SshDiscovery.Status.AUTH_FAILED, selected.completeResults.get(SECOND_PC.endpointKey()).status);
            assertTrue(InspectionSelection.credentialWasRejected(selectedRequest, List.of(selectedRequest, rejectedRequest), selected.completeResults));
            assertFalse(InspectionSelection.credentialWasRejected(otherRequest, List.of(selectedRequest, rejectedRequest), selected.completeResults));
            selected.requireCompleteEvidence(results);
            assertThrows(IllegalArgumentException.class, () -> selected.requireCompleteEvidence(selected.results));
        }
    }

    @Test public void emptySelectionIsNotUnrestrictedRepairAndUnknownAddressesAreRejected() {
        Map<String, SshDiscovery.Result> results = Map.of(FIRST_PC.endpointKey(), result(firstIdentity, FIRST_PC, List.of(FIRST)));
        InspectionSelection empty = InspectionSelection.from(results, Collections.emptyList());
        assertTrue(empty.addresses.isEmpty()); assertTrue(empty.pcIds.isEmpty()); assertTrue(empty.results.isEmpty());
        assertEquals(1, empty.completeResults.size());
        assertFalse(empty.includesPc(results.get(FIRST_PC.endpointKey()).pcMetadata.pcId));
        assertThrows(IllegalArgumentException.class, () -> InspectionSelection.from(results, List.of(SECOND)));
        assertThrows(IllegalArgumentException.class, () -> InspectionSelection.from(results, null));
    }

    @Test public void snapshotsAndSelectedCollectionsCannotBeChangedAfterApproval() {
        Map<String, SshDiscovery.Result> results = new LinkedHashMap<>();
        results.put(FIRST_PC.endpointKey(), result(firstIdentity, FIRST_PC, List.of(FIRST)));
        List<String> choices = new ArrayList<>(List.of(FIRST));
        InspectionSelection selection = InspectionSelection.from(results, choices);
        choices.clear(); results.clear();
        assertEquals(List.of(FIRST), selection.addresses);
        assertEquals(1, selection.completeResults.size());
        assertThrows(UnsupportedOperationException.class, () -> selection.addresses.clear());
        assertThrows(UnsupportedOperationException.class, () -> selection.metadata.clear());
        assertThrows(UnsupportedOperationException.class, () -> selection.results.clear());
        assertThrows(UnsupportedOperationException.class, () -> selection.completeResults.clear());
    }

    private static SshDiscovery.Result result(SshDiscovery.Identity identity, DiscoveryPolicy.Candidate endpoint, List<String> addresses) {
        List<NodeBinding> bindings = new ArrayList<>();
        for (int i = 0; i < addresses.size(); i++) bindings.add(new NodeBinding("docker", null, String.valueOf((char) ('a' + i)).repeat(64), addresses.get(i)));
        return new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, addresses, 0,
                PcMetadata.observed(identity, endpoint.address, endpoint.name, endpoint.address, 1000), null, bindings);
    }
    private static SshDiscovery.Credential credential(String secret) {
        return new SshDiscovery.Credential("fixture", secret.getBytes(StandardCharsets.UTF_8), null, null);
    }
    private static SshDiscovery.Identity identity() {
        com.jcraft.jsch.KeyPair pair = null;
        try {
            pair = com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048);
            return new SshDiscovery.Identity(java.util.Base64.getEncoder().encodeToString(pair.getPublicKeyBlob()), "ssh-rsa");
        } catch (Exception error) { throw new AssertionError(error); }
        finally { if (pair != null) pair.dispose(); }
    }
}
