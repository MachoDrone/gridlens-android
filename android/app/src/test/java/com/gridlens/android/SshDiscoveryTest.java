package com.gridlens.android;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.junit.Test;
import java.io.*;
import java.net.*;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public final class SshDiscoveryTest {
    private static final String ADDRESS = "1".repeat(32);
    private static final String NODE = "a".repeat(64) + "\tnosana/nosana-node:latest\tnosana-node\n";
    private static final String PROXY = "b".repeat(64) + "\tnosana/frpc:0.1.0\tfrpc-api-" + ADDRESS + "\n";
    private static final String WRAPPER = "c".repeat(64) + "\tnosana/podman:latest\tcustom-wrapper\n";
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static final class Fixture implements AutoCloseable, SshDiscovery.Transport {
        final SshServer server;
        final AtomicInteger passwords = new AtomicInteger();
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final Map<String, String> outputs = new HashMap<>();
        final Map<String, Integer> exits = new HashMap<>();
        final Set<String> stalled = new HashSet<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        Fixture() throws Exception {
            server = SshServer.setUpDefaultServer(); server.setHost("127.0.0.1"); server.setPort(0);
            KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA"); keys.initialize(2048);
            server.setKeyPairProvider(KeyPairProvider.wrap(keys.generateKeyPair()));
            server.setPasswordAuthenticator((user, password, session) -> { passwords.incrementAndGet(); return user.equals("operator") && password.equals("fixture-password"); });
            server.setPublickeyAuthenticator((user,key,session) -> user.equals("operator"));
            server.setCommandFactory((channel, command) -> new Command() {
                OutputStream out; ExitCallback exit;
                public void setInputStream(InputStream input) { }
                public void setOutputStream(OutputStream output) { out = output; }
                public void setErrorStream(OutputStream error) { }
                public void setExitCallback(ExitCallback callback) { exit = callback; }
                public void start(ChannelSession channel, Environment environment) throws IOException {
                    commands.add(command);
                    if (stalled.contains(command)) return;
                    out.write(bytes(outputs.getOrDefault(command, ""))); out.flush();
                    exit.onExit(exits.getOrDefault(command, outputs.containsKey(command) ? 0 : 127));
                }
                public void destroy(ChannelSession channel) { }
            });
            outputs.put(DiscoveryPolicy.inventoryCommand("docker", null, false), NODE + PROXY);
            outputs.put(DiscoveryPolicy.nodeApiCommand("docker", null, "a".repeat(64), false), ADDRESS + "\n");
            outputs.put(PcMetadata.NAME_COMMAND, "fixture-pc\n");
            outputs.put(PcMetadata.ADDRESSES_COMMAND, "192.0.2.10 198.51.100.5 fd42::10\n");
            server.start();
        }
        DiscoveryPolicy.Candidate candidate() { return new DiscoveryPolicy.Candidate("192.0.2.10", server.getPort(), "fixture"); }
        public Socket connect(String ignored, int port, int timeout) throws IOException {
            ensureActive(); Socket socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), timeout); socket.setSoTimeout(timeout); return socket;
        }
        public void ensureActive() throws IOException { if (cancelled.get()) throw new IOException("cancelled"); }
        public boolean isCancelled() { return cancelled.get(); }
        public void close() throws Exception { server.stop(true); }
    }
    @Test public void firstUseAndChangedIdentityNeverSendCredentials() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture other = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            assertTrue(identity.fingerprint.startsWith("SHA256:"));
            assertEquals(0, fixture.passwords.get()); assertTrue(fixture.commands.isEmpty());
            SshDiscovery.Result result = SshDiscovery.inventory(other, other.candidate(), identity, credential, null);
            assertEquals(SshDiscovery.Status.IDENTITY_CHANGED, result.status);
            assertEquals(0, other.passwords.get()); assertTrue(other.commands.isEmpty());
        }
    }
    @Test public void readsMultipleNodesAndWrappersUsingOnlyFixedCommands() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.inventoryCommand("docker", null, false), NODE + PROXY + WRAPPER);
            fixture.outputs.put(DiscoveryPolicy.inventoryCommand("docker", "c".repeat(64), false), NODE + PROXY.replace(ADDRESS, "1".repeat(31) + "2"));
            fixture.outputs.put(DiscoveryPolicy.nodeApiCommand("docker", "c".repeat(64), "a".repeat(64), false), "1".repeat(31) + "2\n");
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential, null);
            assertEquals(SshDiscovery.Status.SUCCESS, result.status); assertEquals(2, result.addresses.size()); assertEquals(0, result.unresolved);
            assertEquals(Arrays.asList(DiscoveryPolicy.inventoryCommand("docker", null, false), DiscoveryPolicy.nodeApiCommand("docker", null, "a".repeat(64), false), DiscoveryPolicy.inventoryCommand("docker", "c".repeat(64), false), DiscoveryPolicy.nodeApiCommand("docker", "c".repeat(64), "a".repeat(64), false), DiscoveryPolicy.inventoryCommand("podman", null, false), PcMetadata.NAME_COMMAND, PcMetadata.ADDRESSES_COMMAND), fixture.commands);
            assertEquals(2, result.bindings.size());
            assertEquals("docker", result.bindings.get(0).runtime);
            assertEquals("podman", result.bindings.get(1).runtime);
            assertEquals("c".repeat(64), result.bindings.get(1).wrapperId);
            assertEquals("fixture-pc", result.pcMetadata.pcName);
            assertEquals(Arrays.asList("192.0.2.10", "198.51.100.5", "fd42:0:0:0:0:0:0:10"), result.pcMetadata.ipAddresses);
            assertTrue(result.pcMetadata.pcId.matches("[a-f0-9]{64}"));
            assertTrue(System.currentTimeMillis() - result.pcMetadata.observedAtMs < 5000);
        }
    }
    @Test public void wrongPasswordAttemptsOnceAndNoInventoryRuns() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("wrong"), null, null)) {
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential, null);
            assertEquals(SshDiscovery.Status.AUTH_FAILED, result.status); assertEquals(1, fixture.passwords.get()); assertTrue(fixture.commands.isEmpty());
            assertNull(result.pcMetadata);
        }
    }
    @Test public void optionalPcMetadataFailurePreservesPublicIdentityAndVerifiedEndpoint() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.remove(PcMetadata.NAME_COMMAND); fixture.outputs.remove(PcMetadata.ADDRESSES_COMMAND);
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential, null);
            assertEquals(SshDiscovery.Status.SUCCESS, result.status);
            assertEquals(Collections.singletonList(ADDRESS), result.addresses);
            assertNull(result.pcMetadata.pcName);
            assertEquals(Collections.singletonList("192.0.2.10"), result.pcMetadata.ipAddresses);
        }
    }
    @Test public void remoteMetadataCannotInjectMarkupHostnamesOrUnboundedAddresses() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(PcMetadata.NAME_COMMAND, "<script>invalid()</script>\n");
            fixture.outputs.put(PcMetadata.ADDRESSES_COMMAND, "127.0.0.1 0.0.0.0 0.2.3.4 224.0.0.1 240.0.0.1 198.51.100.4%invalid example.test bad 192.0.2.10 198.51.100.7 ::1 fd42::12\n");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null);
            assertNull(result.pcMetadata.pcName);
            assertEquals(Arrays.asList("192.0.2.10", "198.51.100.7", "fd42:0:0:0:0:0:0:12"), result.pcMetadata.ipAddresses);
            fixture.outputs.put(PcMetadata.NAME_COMMAND, "x".repeat(PcMetadata.MAX_RESPONSE_BYTES + 1));
            result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null);
            assertEquals(SshDiscovery.Status.SUCCESS, result.status);
            assertEquals(Collections.singletonList(ADDRESS), result.addresses);
            assertNull(result.pcMetadata.pcName);
            assertEquals(Collections.singletonList("192.0.2.10"), result.pcMetadata.ipAddresses);
        }
    }
    @Test public void pcMetadataConflictIsExplicitAndCannotBeOverwrittenByAnotherDuplicate() throws Exception {
        try (Fixture fixture = new Fixture(); Fixture other = new Fixture()) {
            PcMetadata first = PcMetadata.observed(SshDiscovery.inspectIdentity(fixture, fixture.candidate()), "192.0.2.10", "fixture-pc", "198.51.100.7", 1000);
            PcMetadata second = PcMetadata.observed(SshDiscovery.inspectIdentity(other, other.candidate()), "192.0.2.11", "other-pc", "198.51.100.8", 2000);
            SshDiscovery.Result one = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of(ADDRESS), 0, first);
            SshDiscovery.Result two = new SshDiscovery.Result(SshDiscovery.Status.SUCCESS, List.of(ADDRESS), 0, second);
            PcMetadata conflict = InspectionResults.metadataByAddress(Arrays.asList(one, two, one)).get(ADDRESS);
            assertNotNull(conflict); assertNull(conflict.pcId); assertNull(conflict.pcName); assertTrue(conflict.ipAddresses.isEmpty());
            assertEquals(2000, conflict.observedAtMs);
            assertEquals(first, InspectionResults.merge(one, SshDiscovery.Result.of(SshDiscovery.Status.UNREACHABLE)).pcMetadata);
        }
    }
    @Test public void laterMetadataDoesNotRenewOldNamesOrAddresses() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            PcMetadata first = PcMetadata.observed(identity, "192.0.2.10", "old-name", "198.51.100.7", 1000);
            PcMetadata second = PcMetadata.observed(identity, "192.0.2.11", null, null, 2000);
            PcMetadata latest = PcMetadata.merge(first, second);
            assertEquals(first.pcId, latest.pcId); assertNull(latest.pcName);
            assertEquals(List.of("192.0.2.11"), latest.ipAddresses); assertEquals(2000, latest.observedAtMs);
        }
    }
    @Test public void ordinaryGpuReadDoesNotProvisionAndFailureKeepsPublicDiscoveries() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(GpuCollector.listContainer(false), "");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null, "fixture-source", false);
            assertEquals(SshDiscovery.Status.SUCCESS, result.status);
            assertEquals(List.of(ADDRESS), result.addresses);
            assertEquals(GpuCollector.Status.NOT_INSTALLED, result.gpuCollectorStatus);
            assertTrue(result.pcMetadata.gpuTelemetryJson.contains("not-enabled"));
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains(" volume create ") || command.contains(" run --detach ") || command.contains(" pull ")));
            fixture.exits.put(GpuCollector.listContainer(false), 127);
            result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null, "fixture-source", true);
            assertEquals(List.of(ADDRESS), result.addresses);
            assertEquals(SshDiscovery.Status.SUCCESS, result.status);
            assertEquals(GpuCollector.Status.UNAVAILABLE, result.gpuCollectorStatus);
        }
    }
    @Test public void deniedSudoAndUnresolvedNodesRemainHonest() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.exits.put(DiscoveryPolicy.inventoryCommand("docker", null, true), 1);
            fixture.exits.put(DiscoveryPolicy.inventoryCommand("podman", null, true), 1);
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            assertEquals(SshDiscovery.Status.CONTAINER_ACCESS_REQUIRED, SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, bytes("denied")).status);
            fixture.outputs.put(DiscoveryPolicy.inventoryCommand("docker", null, false), NODE);
            fixture.outputs.remove(DiscoveryPolicy.nodeApiCommand("docker", null, "a".repeat(64), false));
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null);
            assertEquals(1, result.unresolved); assertTrue(result.addresses.isEmpty());
        }
    }
    @Test public void rsaEcdsaEd25519AndEncryptedKeysAuthenticateWithoutPasswordFallback() throws Exception {
        SshDiscovery.configureCryptoForAndroid();
        try (Fixture fixture = new Fixture()) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            for (int type : new int[] {KeyPair.RSA, KeyPair.ECDSA, KeyPair.ED25519}) {
                KeyPair key = KeyPair.genKeyPair(new JSch(), type, type == KeyPair.RSA ? 2048 : 256);
                try {
                    for (byte[] passphrase : new byte[][] {null, bytes("fixture-passphrase")}) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream(); if (type == KeyPair.ED25519) key.writeOpenSSHv1PrivateKey(output, passphrase); else key.writePrivateKey(output, passphrase);
                        try (SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", null, output.toByteArray(), passphrase)) {
                            SshDiscovery.validateCredential(credential);
                            assertEquals("key type " + type, SshDiscovery.Status.SUCCESS, SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null).status);
                        }
                        if (passphrase != null) try (SshDiscovery.Credential wrong = new SshDiscovery.Credential("operator", null, output.toByteArray(), bytes("wrong"))) {
                            assertThrows(IllegalArgumentException.class, () -> SshDiscovery.validateCredential(wrong));
                        }
                    }
                } finally { key.dispose(); }
            }
            assertEquals(0, fixture.passwords.get());
        }
    }
    @Test public void unresolvedNodeUsesOnlyAlreadyRunningLocalApiAndRejectsNonAddresses() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.inventoryCommand("docker", null, false), NODE);
            String api = DiscoveryPolicy.nodeApiCommand("docker", null, "a".repeat(64), false);
            fixture.outputs.put(api, ADDRESS + "\n");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null);
            assertEquals(Collections.singletonList(ADDRESS), result.addresses); assertEquals(0, result.unresolved);
            assertTrue(fixture.commands.contains(api));
            assertTrue(api.contains("/usr/bin/env -i /usr/local/bin/node -e '"));
            assertFalse(api.contains("nosana-node address")); assertFalse(api.contains("process.env"));
            fixture.outputs.put(api, "not-a-public-address\n");
            result = SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null);
            assertTrue(result.addresses.isEmpty()); assertEquals(1, result.unresolved);
        }
    }
    @Test public void excessiveRemoteOutputIsRejectedWithoutExposingItsContents() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.inventoryCommand("docker", null, false), "x".repeat(DiscoveryPolicy.MAX_OUTPUT + 1));
            SshDiscovery.Result result = SshDiscovery.inventory(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential, null);
            assertEquals(SshDiscovery.Status.INVENTORY_FAILED, result.status); assertTrue(result.addresses.isEmpty());
        }
    }
    @Test public void batchStopsAfterAuthenticationFailureAndNeverExceedsFourSessions() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            List<SshDiscovery.BatchRequest> requests = new ArrayList<>();
            for (int i = 0; i < 40; i++) requests.add(new SshDiscovery.BatchRequest(fixture.candidate(), identity, credential, null));
            AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), calls = new AtomicInteger();
            CountDownLatch firstWave = new CountDownLatch(4);
            List<SshDiscovery.Result> results = SshDiscovery.runBatch(requests, null, request -> {
                int n = active.incrementAndGet(); peak.accumulateAndGet(n, Math::max); calls.incrementAndGet(); firstWave.countDown();
                try { assertTrue(firstWave.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                active.decrementAndGet(); return SshDiscovery.Result.of(SshDiscovery.Status.AUTH_FAILED);
            }, () -> false);
            assertEquals(4, peak.get()); assertEquals(4, calls.get());
            assertEquals(36, results.stream().filter(r -> r.status == SshDiscovery.Status.CANCELLED).count());
        }
    }
    @Test public void cancelledNetworkStartsNoAuthentication() throws Exception {
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate()); fixture.cancelled.set(true);
            assertEquals(SshDiscovery.Status.CANCELLED, SshDiscovery.inventory(fixture, fixture.candidate(), identity, credential, null).status);
            assertEquals(0, fixture.passwords.get());
        }
    }
    @Test public void monitoringRechecksImmutableContainerAndOwnerWithoutRediscovery() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            List<SshDiscovery.MonitoringResult> result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), null, null, false, false, false, Map.of());
            assertEquals("active", result.get(0).status);
            assertEquals(List.of(DiscoveryPolicy.bindingInspectionCommand(binding, false), DiscoveryPolicy.bindingAddressCommand(binding, false)), fixture.commands);
            fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), "1".repeat(31) + "2\n");
            result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), "never-used", "never-used", true, true, true, Map.of());
            assertEquals("binding-changed", result.get(0).status); assertTrue(result.get(0).suspendRewards);
            assertTrue(result.get(0).gpuJson.contains("binding-mismatch")); assertNull(result.get(0).rewardsJson);
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains("gridlens-approved-rewards") || command.contains(" run --detach ")));
        }
    }
    @Test public void monitoringDoesNotGuessGpuMappingOrUseHostNetwork() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        String gpu = "GPU-00000000-1111-2222-3333-444444444444";
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
            fixture.outputs.put(DiscoveryPolicy.bindingGpuCommand(binding, false), gpu + "\n");
            fixture.outputs.put(DiscoveryPolicy.bindingGpuInfoCommand(binding, false), gpu.replace("444444444444", "555555555555") + "\n");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.MonitoringResult result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), null, "fixture", true, false, true, Map.of()).get(0);
            assertTrue(result.gpuJson.contains("binding-mismatch"));
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains(" run --detach ")));
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\thost\n");
            result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), null, "fixture", true, false, true, Map.of()).get(0);
            assertEquals("unsupported", result.status);
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains("--network host")));
        }
    }
    @Test public void nestedNodeUsesOnlyVerifiedIsolatedWrapperNamespace() throws Exception {
        NodeBinding binding = new NodeBinding("podman", "c".repeat(64), "a".repeat(64), ADDRESS);
        String gpu = "GPU-00000000-1111-2222-3333-444444444444";
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.wrapperInspectionCommand(binding, false), binding.wrapperId + "\ttrue\tnosana/podman:latest\tbridge\n");
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\thost\n");
            fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), ADDRESS + "\n");
            fixture.outputs.put(DiscoveryPolicy.bindingGpuCommand(binding, false), gpu + "\n");
            fixture.outputs.put(DiscoveryPolicy.bindingGpuInfoCommand(binding, false), gpu + "\n");
            NodeGpuCollector policy = new NodeGpuCollector(binding, binding.wrapperId, List.of(gpu), null);
            fixture.outputs.put(policy.listContainer(false), "");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.MonitoringResult result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), null, "fixture", false, false, true, Map.of()).get(0);
            assertTrue(result.gpuJson.contains("not-enabled"));
            assertTrue(fixture.commands.contains(policy.listContainer(false)));
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains(" run --detach ")));
        }
    }
    @Test public void rewardsPermissionIsScopedToExactAddressAndChangedSshKeySendsNothing() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        try (Fixture fixture = new Fixture(); Fixture other = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.MonitoringResult result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), "must-not-execute", null, false, true, false, Map.of(), Set.of()).get(0);
            assertNull(result.rewardsJson);
            assertFalse(fixture.commands.stream().anyMatch(command -> command.contains("must-not-execute")));
            result = SshDiscovery.monitor(other, other.candidate(), identity, credential, null, List.of(binding), "must-not-execute", null, false, true, false, Map.of()).get(0);
            assertEquals("identity-changed", result.status);
            assertEquals(0, other.passwords.get()); assertTrue(other.commands.isEmpty());
        }
    }
    private static RewardsReader.Binding monitoringRewardFixture(Fixture fixture, NodeBinding binding, String status) {
        fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
        fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), binding.address + "\n");
        RewardsReader.Binding reward = new RewardsReader.Binding(binding.runtime, binding.wrapperId, binding.nodeContainerId, binding.address);
        fixture.outputs.put(RewardsReader.inspectNode(reward, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\n");
        fixture.outputs.put(RewardsReader.identityCommand(reward, false), binding.address + "\n");
        fixture.outputs.put(RewardsReader.readCommand("fixture-reward", reward, false), "{\"schema\":1,\"address\":\"" + binding.address + "\",\"observedAtMs\":" + System.currentTimeMillis() + ",\"status\":\"" + status + "\""
                + (status.equals("rate-limited") ? ",\"retryAfterSeconds\":300" : ",\"amount\":\"1.000000\"") + "}");
        return reward;
    }
    @Test public void providerPauseIsPersistedBeforeNextBindingAndStopsBatchRewardReads() throws Exception {
        NodeBinding first = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding second = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            monitoringRewardFixture(fixture, first, "rate-limited");
            RewardsReader.Binding next = monitoringRewardFixture(fixture, second, "ok");
            AtomicBoolean sawPause = new AtomicBoolean();
            List<SshDiscovery.MonitoringResult> result = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                    null, List.of(first, second), "fixture-reward", null, false, true, false, Map.of(), null,
                    current -> { if (current.retryAfterSeconds > 0) { assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", next, false))); sawPause.set(true); } }, () -> true);
            assertTrue(sawPause.get()); assertEquals(300, result.get(0).retryAfterSeconds);
            assertNull(result.get(1).rewardsJson);
            assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", next, false)));
        }
    }
    @Test public void persistenceFailureOrGlobalPausePreventsFurtherKeyUse() throws Exception {
        NodeBinding first = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding second = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            RewardsReader.Binding one = monitoringRewardFixture(fixture, first, "ok");
            RewardsReader.Binding two = monitoringRewardFixture(fixture, second, "ok");
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(first, second), "fixture-reward", null,
                    false, true, false, Map.of(), null, current -> {}, () -> false);
            assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", one, false)));
            assertThrows(RuntimeException.class, () -> SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null,
                    List.of(first, second), "fixture-reward", null, false, true, false, Map.of(), null,
                    current -> { throw new IllegalStateException("fixture save failure"); }, () -> true));
            assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", two, false)));
        }
    }
    @Test public void networkObservationUsesOneVerifiedNodePerPcAndOriginalTimesForEveryBinding() throws Exception {
        NodeBinding first = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding second = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            for (NodeBinding binding : List.of(first, second)) {
                fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
                fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), binding.address + "\n");
            }
            fixture.outputs.put(PcMetadata.ADDRESSES_COMMAND, "192.168.3.20 172.17.0.1\n");
            fixture.outputs.put(PublicIpReader.command(first, false), "8.8.8.8\n");
            AtomicInteger lookups = new AtomicInteger();
            SshDiscovery.NetworkLookup lookup = (binding, runner) -> {
                assertSame(first, binding); lookups.incrementAndGet();
                assertTrue(fixture.commands.contains(DiscoveryPolicy.bindingAddressCommand(first, false)));
                return PublicIpReader.read(binding, fixture.candidate().address, false, runner, () -> 1000);
            };
            List<SshDiscovery.MonitoringResult> result = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                    null, List.of(first, second), null, null, false, false, false, Map.of(), null, current -> {}, () -> true, lookup);
            assertEquals(1, lookups.get()); assertEquals(result.get(0).networkJson, result.get(1).networkJson);
            PublicIpReader.Observation observed = PublicIpReader.parse(result.get(0).networkJson);
            assertEquals("8.8.8.8", observed.publicIp); assertEquals("192.168.3.20", observed.privateIp); assertEquals(1000, observed.observedAtMs);
            assertFalse(fixture.commands.contains(PublicIpReader.command(second, false)));
        }
    }
    @Test public void publicIpLookupNeverRunsWhenHostOrNodeIdentityFailsVerification() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        try (Fixture fixture = new Fixture(); Fixture other = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            SshDiscovery.NetworkLookup denied = (ignored, runner) -> { fail("No public-IP request before exact trust and binding proof"); return null; };
            List<SshDiscovery.MonitoringResult> result = SshDiscovery.monitor(other, other.candidate(), identity, credential, null, List.of(binding), null, null,
                    false, false, false, Map.of(), null, current -> {}, () -> true, denied);
            assertEquals("identity-changed", result.get(0).status); assertNull(result.get(0).networkJson);
            fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
            fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), "1".repeat(31) + "2\n");
            result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding), null, null,
                    false, false, false, Map.of(), null, current -> {}, () -> true, denied);
            assertEquals("binding-changed", result.get(0).status); assertNull(result.get(0).networkJson);
        }
    }
    @Test public void gpuDockerAccessFailurePreservesRewardsAndSharedPublicIpWithoutRejectingSshProfile() throws Exception {
        NodeBinding first = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding second = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        String gpu = "GPU-00000000-1111-2222-3333-444444444444";
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            RewardsReader.Binding one = monitoringRewardFixture(fixture, first, "ok");
            RewardsReader.Binding two = monitoringRewardFixture(fixture, second, "ok");
            for (NodeBinding binding : List.of(first, second)) {
                fixture.outputs.put(DiscoveryPolicy.bindingGpuCommand(binding, false), gpu + "\n");
                fixture.outputs.put(DiscoveryPolicy.bindingGpuInfoCommand(binding, false), gpu + "\n");
                NodeGpuCollector collector = new NodeGpuCollector(binding, binding.nodeContainerId, List.of(gpu), null);
                fixture.outputs.put(collector.listContainer(false), "");
                fixture.exits.put(collector.listContainer(false), 1); // Docker recorder access, after successful SSH/node reads.
            }
            AtomicBoolean blocked = new AtomicBoolean();
            String network = new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, "10.0.0.12", 1000L).json();
            List<SshDiscovery.MonitoringResult> results = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                    null, List.of(first, second), "fixture-reward", "fixture-gpu", false, true, true, Map.of(), null,
                    result -> { if (MonitoringPolicy.suspendsCredentialProfile(result.status)) { blocked.set(true); fixture.cancelled.set(true); } },
                    () -> !blocked.get(), (binding, runner) -> PublicIpReader.parse(network));
            assertFalse("Recorder access must not cancel every PC sharing this SSH credential", blocked.get());
            assertEquals(2, results.size()); assertEquals(1, fixture.passwords.get());
            assertEquals(fixture.outputs.get(RewardsReader.readCommand("fixture-reward", one, false)), results.get(0).rewardsJson);
            assertEquals(fixture.outputs.get(RewardsReader.readCommand("fixture-reward", two, false)), results.get(1).rewardsJson);
            for (SshDiscovery.MonitoringResult result : results) {
                assertEquals("gpu-access-required", result.status); assertFalse(result.suspendRewards);
                assertEquals(network, result.networkJson); assertTrue(result.gpuJson.contains("unavailable"));
            }
        }
    }
    @Test(timeout = 25000) public void gpuCommandTimeoutCannotDiscardEarlierSuccessfulRewardsOrNetworkReading() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            RewardsReader.Binding reward = monitoringRewardFixture(fixture, binding, "ok");
            fixture.stalled.add(DiscoveryPolicy.bindingGpuCommand(binding, false));
            String expected = fixture.outputs.get(RewardsReader.readCommand("fixture-reward", reward, false));
            String network = new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, "10.0.0.12", 1000L).json();
            SshDiscovery.MonitoringResult result = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                    null, List.of(binding), "fixture-reward", "fixture-gpu", false, true, true, Map.of(), null, ignored -> {}, () -> true,
                    (node, runner) -> PublicIpReader.parse(network)).get(0);
            assertEquals(expected, result.rewardsJson); assertEquals(network, result.networkJson);
            assertEquals("active", result.status); assertFalse(result.suspendRewards); assertTrue(result.gpuJson.contains("unavailable"));
        }
    }
    @Test public void cancelledAuthenticationCannotPersistARejectedSharedCredential() throws Exception {
        NodeBinding binding = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            SshDiscovery.Identity identity = SshDiscovery.inspectIdentity(fixture, fixture.candidate());
            fixture.server.setPasswordAuthenticator((user, password, session) -> {
                fixture.passwords.incrementAndGet(); fixture.cancelled.set(true); return false;
            });
            AtomicInteger rejectedProfile = new AtomicInteger();
            SshDiscovery.MonitoringResult result = SshDiscovery.monitor(fixture, fixture.candidate(), identity, credential, null, List.of(binding),
                    null, null, false, false, false, Map.of(), null,
                    event -> { if (MonitoringPolicy.suspendsCredentialProfile(event.status)) rejectedProfile.incrementAndGet(); }, () -> true).get(0);
            assertEquals("unreachable", result.status); assertEquals(0, rejectedProfile.get()); assertTrue(fixture.commands.isEmpty());
        }
    }
    @Test public void sharedNetworkReadingIsAttachedOnlyToCurrentlyVerifiedNodeBindings() throws Exception {
        NodeBinding changed = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding verified = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        for (List<NodeBinding> order : List.of(List.of(changed, verified), List.of(verified, changed))) {
            try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
                for (NodeBinding binding : order) {
                    fixture.outputs.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\tbridge\n");
                    fixture.outputs.put(DiscoveryPolicy.bindingAddressCommand(binding, false), verified.address + "\n");
                }
                AtomicInteger lookups = new AtomicInteger();
                String network = new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, "10.0.0.12", 1000L).json();
                List<SshDiscovery.MonitoringResult> results = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                        null, order, null, null, false, false, false, Map.of(), null, ignored -> {}, () -> true,
                        (binding, runner) -> { assertSame(verified, binding); lookups.incrementAndGet(); return PublicIpReader.parse(network); });
                assertEquals(1, lookups.get());
                for (SshDiscovery.MonitoringResult result : results) {
                    if (result.binding == verified) assertEquals(network, result.networkJson);
                    else { assertEquals("binding-changed", result.status); assertNull("A changed node cannot acquire this PC's current IP timestamp", result.networkJson); }
                }
            }
        }
    }
    @Test(timeout = 25000) public void allVerifiedIpsAreDeliveredBeforeAStalledFirstGpuOrAnyRewardRead() throws Exception {
        NodeBinding first = new NodeBinding("docker", null, "a".repeat(64), ADDRESS);
        NodeBinding second = new NodeBinding("docker", null, "b".repeat(64), "1".repeat(31) + "2");
        try (Fixture fixture = new Fixture(); SshDiscovery.Credential credential = new SshDiscovery.Credential("operator", bytes("fixture-password"), null, null)) {
            RewardsReader.Binding one = monitoringRewardFixture(fixture, first, "ok");
            RewardsReader.Binding two = monitoringRewardFixture(fixture, second, "ok");
            fixture.stalled.add(DiscoveryPolicy.bindingGpuCommand(first, false));
            List<String> delivered = new ArrayList<>();
            String network = new PublicIpReader.Observation(1000, "8.8.8.8", 1000L, null, null).json();
            List<SshDiscovery.MonitoringResult> results = SshDiscovery.monitor(fixture, fixture.candidate(), SshDiscovery.inspectIdentity(fixture, fixture.candidate()), credential,
                    null, List.of(first, second), "fixture-reward", "fixture-gpu", false, true, true, Map.of(), null,
                    result -> {
                        if (result.networkJson != null) {
                            assertFalse(fixture.commands.contains(DiscoveryPolicy.bindingGpuCommand(first, false)));
                            assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", one, false)));
                            assertFalse(fixture.commands.contains(RewardsReader.readCommand("fixture-reward", two, false)));
                            assertNull(result.gpuJson); assertNull(result.rewardsJson); delivered.add(result.binding.address);
                        }
                        if (result.rewardsJson != null || result.gpuJson != null) assertEquals(List.of(first.address, second.address), delivered);
                    }, () -> true, (node, runner) -> PublicIpReader.parse(network));
            assertEquals(List.of(first.address, second.address), delivered);
            assertEquals(2, results.size());
        }
    }

}
