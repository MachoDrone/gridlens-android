package com.gridlens.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class NodeGpuCollectorTest {
    private static final String OWNED = "d".repeat(64);
    private static final String UUID = "GPU-00000000-1111-2222-3333-444444444444";
    private static final NodeBinding BINDING = new NodeBinding("docker", null, "a".repeat(64), "1".repeat(32));
    private static final NodeGpuCollector POLICY = new NodeGpuCollector(BINDING, BINDING.nodeContainerId, List.of(UUID), null);
    private static final String SOURCE = POLICY.boundSource("print('fixture only')\n");
    private static final String JSON = NodeGpuCollector.unavailable(BINDING, "starting");
    private static final class Fixture implements NodeGpuCollector.Runner {
        final List<String> commands = new ArrayList<>();
        final Map<String, NodeGpuCollector.Response> responses = new HashMap<>();
        final boolean sudo;
        Fixture(boolean sudo) {
            this.sudo = sudo;
            put(POLICY.listContainer(sudo), 0, POLICY.CONTAINER);
            put(POLICY.inspectContainer(SOURCE, sudo), 0, inspection(true));
            put(POLICY.inspectVolume(sudo), 0, "android\tnode-gpu-recorder\t1\tlocal\tlocal\tnull\n");
            put(POLICY.runningCommand(sudo, OWNED), 0, "true\n");
            put(POLICY.targetNamespace(sudo), 0, "net:[100]\n");
            put(POLICY.recorderNamespace(sudo, OWNED), 0, "net:[100]\n");
            put(POLICY.readTelemetry(SOURCE, sudo, OWNED), 0, JSON);
        }
        void put(String command, int exit, String output) { responses.put(command, new NodeGpuCollector.Response(exit, output)); }
        String inspection(boolean running) {
            return "android\tnode-gpu-recorder\t1\t" + POLICY.hash(SOURCE) + "\t" + NodeGpuCollector.IMAGE
                + "\tcontainer:" + BINDING.nodeContainerId + "\ttrue\tfalse\tvolume:" + POLICY.VOLUME + ":/data:true;\t" + running
                + "\t\tprivate\t\tnull\t[\"ALL\"]\t[\"no-new-privileges\"]\tnull\t{}\tnull\tnull\t0::" + UUID + ",:gpu,:{};\tnull\ttrue"
                + "\t134217728\t250000000\t32\tunless-stopped\tNVIDIA_DRIVER_CAPABILITIES=utility;\t" + OWNED + "\n";
        }
        public NodeGpuCollector.Response execute(String command, int maxBytes, long timeoutMs) {
            commands.add(command);
            assertTrue(maxBytes <= NodeGpuCollector.MAX_TELEMETRY_BYTES);
            assertTrue(timeoutMs <= 150000);
            NodeGpuCollector.Response response = responses.get(command);
            assertNotNull("Unexpected command: " + command, response);
            if (command.equals(POLICY.restartContainer(sudo, OWNED)) && response.exit == 0)
                responses.put(POLICY.recorderNamespace(sudo, OWNED), responses.get(POLICY.targetNamespace(sudo)));
            return response;
        }
        NodeGpuCollector.Outcome collect(boolean enable) { return POLICY.collect(SOURCE, enable, sudo, this); }
    }

    @Test public void ordinaryRescanReadsOnlyValidatedExistingContainerAndVolume() {
        Fixture fixture = new Fixture(false);
        assertEquals(JSON, fixture.collect(false).json);
        assertEquals(List.of(POLICY.listContainer(false), POLICY.inspectContainer(SOURCE, false),
                POLICY.inspectVolume(false), POLICY.targetNamespace(false), POLICY.recorderNamespace(false, OWNED), POLICY.readTelemetry(SOURCE, false, OWNED)), fixture.commands);
    }
    @Test public void ordinaryRescanDoesNotCreateOrStartAnything() {
        Fixture missing = new Fixture(false);
        missing.put(POLICY.listContainer(false), 0, "");
        NodeGpuCollector.Outcome outcome = missing.collect(false);
        assertEquals(NodeGpuCollector.Status.NOT_INSTALLED, outcome.status);
        assertTrue(outcome.json.contains("not-enabled"));
        assertEquals(List.of(POLICY.listContainer(false)), missing.commands);
        Fixture stopped = new Fixture(false);
        stopped.put(POLICY.inspectContainer(SOURCE, false), 0, stopped.inspection(false));
        assertEquals(NodeGpuCollector.Status.STOPPED, stopped.collect(false).status);
        assertFalse(stopped.commands.contains(POLICY.startContainer(false, OWNED)));
    }
    @Test public void approvedInstallationCreatesOnlyOwnedNamedResourcesAndPinnedImage() {
        Fixture fixture = new Fixture(true);
        fixture.put(POLICY.listContainer(true), 0, "");
        fixture.put(POLICY.listVolume(true), 0, "");
        fixture.put(POLICY.createVolume(true), 0, POLICY.VOLUME + "\n");
        fixture.put(POLICY.pullImage(true), 0, "fixture image pulled");
        fixture.put(POLICY.createContainer(SOURCE, true), 0, "a".repeat(64));
        assertEquals(NodeGpuCollector.Status.READ, fixture.collect(true).status);
        String run = POLICY.createContainer(SOURCE, true);
        assertTrue(run.contains("--network container:" + BINDING.nodeContainerId + " --read-only --cap-drop ALL --security-opt no-new-privileges"));
        assertTrue(run.contains("--gpus '\"device=" + UUID + "\"' --env NVIDIA_DRIVER_CAPABILITIES=utility"));
        assertTrue(run.contains("--mount type=volume,source=" + POLICY.VOLUME + ",target=/data"));
        assertTrue(run.contains("--label io.gridlens.collector-sha256=" + POLICY.hash(SOURCE)));
        assertFalse(run.contains("--privileged")); assertFalse(run.contains("--pid host"));
        assertFalse(run.contains("SYS_MODULE")); assertFalse(run.contains("docker.sock"));
        assertFalse(run.contains("type=bind")); assertFalse(run.contains("--publish"));
        assertTrue(fixture.commands.stream().allMatch(command -> command.startsWith("/usr/bin/sudo -S -p '' -- /usr/bin/docker ")));
    }
    @Test public void approvedRerunPreservesVolumeAndResumesOnlyOwnedStoppedRecorder() {
        Fixture fixture = new Fixture(false);
        fixture.put(POLICY.inspectContainer(SOURCE, false), 0, fixture.inspection(false));
        fixture.put(POLICY.startContainer(false, OWNED), 0, POLICY.CONTAINER);
        assertEquals(NodeGpuCollector.Status.READ, fixture.collect(true).status);
        assertTrue(fixture.commands.contains(POLICY.startContainer(false, OWNED)));
        assertFalse(fixture.commands.contains(POLICY.createVolume(false)));
        assertFalse(fixture.commands.contains(POLICY.createContainer(SOURCE, false)));
        assertFalse(fixture.commands.stream().anyMatch(command -> command.contains(" rm ") || command.contains("remove")));
    }
    @Test public void unownedContainerOrVolumeIsNeverExecutedOrReplaced() {
        Fixture container = new Fixture(false);
        container.put(POLICY.inspectContainer(SOURCE, false), 0, container.inspection(true).replace("android\t", "unrelated\t"));
        assertEquals(NodeGpuCollector.Status.OWNERSHIP_CONFLICT, container.collect(true).status);
        assertFalse(container.commands.contains(POLICY.readTelemetry(SOURCE, false, OWNED)));
        Fixture volume = new Fixture(false);
        volume.put(POLICY.listContainer(false), 0, "");
        volume.put(POLICY.listVolume(false), 0, POLICY.VOLUME);
        volume.put(POLICY.inspectVolume(false), 0, "unrelated\tgpu-recorder\t1");
        assertEquals(NodeGpuCollector.Status.OWNERSHIP_CONFLICT, volume.collect(true).status);
        assertFalse(volume.commands.contains(POLICY.createContainer(SOURCE, false)));
    }
    @Test public void changedSourceImageMountsOrPrivilegesFailClosedBeforeExec() {
        for (String field : List.of(POLICY.hash(SOURCE), NodeGpuCollector.IMAGE, "\tcontainer:" + BINDING.nodeContainerId + "\t", "\ttrue\tfalse\t", "volume:" + POLICY.VOLUME + ":/data:true;")) {
            Fixture fixture = new Fixture(false);
            fixture.put(POLICY.inspectContainer(SOURCE, false), 0, fixture.inspection(true).replace(field, "unsupported"));
            assertTrue(fixture.collect(true).status != NodeGpuCollector.Status.READ);
            assertFalse(fixture.commands.contains(POLICY.readTelemetry(SOURCE, false, OWNED)));
            assertFalse(fixture.commands.contains(POLICY.startContainer(false, OWNED)));
        }
    }
    @Test public void localDriverBindOptionsAndHostNamespaceChangesAreRejected() {
        Fixture volume = new Fixture(false);
        volume.put(POLICY.inspectVolume(false), 0, "android\tnode-gpu-recorder\t1\tlocal\tlocal\t{\"device\":\"/etc\",\"o\":\"bind\"}");
        assertEquals(NodeGpuCollector.Status.OWNERSHIP_CONFLICT, volume.collect(true).status);
        assertFalse(volume.commands.contains(POLICY.readTelemetry(SOURCE, false, OWNED)));
        int[] unsafeFields = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27};
        for (int index : unsafeFields) {
            Fixture fixture = new Fixture(false);
            String[] parts = fixture.inspection(true).trim().split("\\t", -1);
            parts[index] = "unsafe-fixture";
            fixture.put(POLICY.inspectContainer(SOURCE, false), 0, String.join("\t", parts));
            assertEquals("field " + index, NodeGpuCollector.Status.CONFIGURATION_MISMATCH, fixture.collect(true).status);
            assertFalse(fixture.commands.contains(POLICY.readTelemetry(SOURCE, false, OWNED)));
        }
    }
    @Test public void startingRecorderWithoutFirstReportIsPendingRatherThanFailedSetup() {
        Fixture fixture = new Fixture(false);
        fixture.put(POLICY.inspectContainer(SOURCE, false), 0, fixture.inspection(false));
        fixture.put(POLICY.startContainer(false, OWNED), 0, POLICY.CONTAINER);
        fixture.put(POLICY.readTelemetry(SOURCE, false, OWNED), 1, "{\"schema\":1,\"collector\":{\"status\":\"unavailable\"},\"gpus\":[]}");
        assertEquals(NodeGpuCollector.Status.STARTED, fixture.collect(true).status);
    }
    @Test public void failedPullNeverStartsContainerAndFailedReadCarriesNoRawOutput() {
        Fixture pull = new Fixture(false);
        pull.put(POLICY.listContainer(false), 0, "");
        pull.put(POLICY.listVolume(false), 0, POLICY.VOLUME);
        pull.put(POLICY.pullImage(false), 1, "sensitive upstream diagnostic fixture");
        NodeGpuCollector.Outcome failed = pull.collect(true);
        assertEquals(NodeGpuCollector.Status.INSTALL_FAILED, failed.status);
        assertFalse(failed.json.contains("diagnostic"));
        assertFalse(pull.commands.contains(POLICY.createContainer(SOURCE, false)));
        Fixture read = new Fixture(false);
        read.put(POLICY.readTelemetry(SOURCE, false, OWNED), 1, "private-error-fixture");
        failed = read.collect(false);
        assertEquals(NodeGpuCollector.Status.READ_FAILED, failed.status);
        assertFalse(failed.json.contains("private-error"));
    }
    @Test public void sourceIsOneQuotedArgumentAndNoUserInputEntersCommands() {
        assertEquals("'a'\\''b\n$HOME`id`'", POLICY.quote("a'b\n$HOME`id`"));
        assertTrue(POLICY.createContainer(SOURCE, false).endsWith("python -B -c " + POLICY.quote(SOURCE) + " run"));
        assertThrows(IllegalArgumentException.class, () -> POLICY.createContainer("x".repeat(NodeGpuCollector.MAX_SOURCE_BYTES + 1), false));
        assertThrows(IllegalArgumentException.class, () -> POLICY.createContainer("x\0", false));
    }
    @Test public void exactBindingAndGpuSelectionArePinnedAndCursorIsNumericOnly() {
        NodeGpuCollector next = new NodeGpuCollector(new NodeBinding("docker", null, "b".repeat(64), BINDING.address), "b".repeat(64), List.of(UUID), 1800000000000L);
        assertNotEquals(POLICY.CONTAINER, next.CONTAINER);
        assertTrue(next.boundSource("fixture").contains("BOUND_NODE_ID = '" + "b".repeat(64) + "'"));
        assertTrue(next.readTelemetry(next.boundSource("fixture"), false).endsWith(" read 1800000000000"));
        assertThrows(IllegalArgumentException.class, () -> new NodeGpuCollector(BINDING, "host", List.of(UUID), null));
        assertThrows(IllegalArgumentException.class, () -> new NodeGpuCollector(BINDING, BINDING.nodeContainerId, List.of(UUID, UUID), null));
        assertThrows(IllegalArgumentException.class, () -> new NodeGpuCollector(BINDING, BINDING.nodeContainerId, List.of("0"), null));
    }
    @Test public void namespaceRepairNeedsRunningOwnedRecorderReverifiedBindingAndCooldown() {
        Fixture unknown = new Fixture(false);
        unknown.put(POLICY.recorderNamespace(false, OWNED), 0, "untrusted-link");
        assertEquals(NodeGpuCollector.Status.READ, POLICY.collect(SOURCE, false, false, unknown, () -> true).status);
        assertFalse(unknown.commands.contains(POLICY.restartContainer(false, OWNED)));
        Fixture denied = new Fixture(false);
        denied.put(POLICY.recorderNamespace(false, OWNED), 0, "net:[99]");
        assertEquals(NodeGpuCollector.Status.CONFIGURATION_MISMATCH, POLICY.collect(SOURCE, false, false, denied, () -> false).status);
        assertFalse(denied.commands.contains(POLICY.restartContainer(false, OWNED)));
        Fixture drift = new Fixture(false);
        drift.put(POLICY.recorderNamespace(false, OWNED), 0, "net:[98]");
        drift.put(POLICY.restartContainer(false, OWNED), 0, OWNED);
        assertEquals(NodeGpuCollector.Status.READ, POLICY.collect(SOURCE, false, false, drift, () -> true).status);
        assertTrue(drift.commands.contains(POLICY.restartContainer(false, OWNED)));
        assertFalse(drift.commands.stream().anyMatch(command -> command.endsWith("restart --time 5 " + BINDING.nodeContainerId)));
        drift.put(POLICY.recorderNamespace(false, OWNED), 0, "net:[97]");
        assertEquals(NodeGpuCollector.Status.READ_FAILED, POLICY.collect(SOURCE, false, false, drift, () -> true).status);
        assertEquals(1, drift.commands.stream().filter(command -> command.equals(POLICY.restartContainer(false, OWNED))).count());
        Fixture stopped = new Fixture(false);
        stopped.put(POLICY.inspectContainer(SOURCE, false), 0, stopped.inspection(false));
        assertEquals(NodeGpuCollector.Status.STOPPED, POLICY.collect(SOURCE, false, false, stopped, () -> true).status);
        assertFalse(stopped.commands.contains(POLICY.restartContainer(false, OWNED)));
    }
}
