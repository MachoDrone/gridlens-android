package com.gridlens.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class GpuCollectorTest {
    private static final String SOURCE = "print('fixture only')\n";
    private static final String JSON = "{\"schema\":1,\"collector\":{\"status\":\"starting\"},\"gpus\":[]}";
    private static final class Fixture implements GpuCollector.Runner {
        final List<String> commands = new ArrayList<>();
        final Map<String, GpuCollector.Response> responses = new HashMap<>();
        final boolean sudo;
        Fixture(boolean sudo) {
            this.sudo = sudo;
            put(GpuCollector.listContainer(sudo), 0, GpuCollector.CONTAINER);
            put(GpuCollector.inspectContainer(SOURCE, sudo), 0, inspection(true));
            put(GpuCollector.inspectVolume(sudo), 0, "android\tgpu-recorder\t1\tlocal\tlocal\tnull\n");
            put(GpuCollector.readTelemetry(SOURCE, sudo), 0, JSON);
        }
        void put(String command, int exit, String output) { responses.put(command, new GpuCollector.Response(exit, output)); }
        String inspection(boolean running) {
            return "android\tgpu-recorder\t1\t" + GpuCollector.hash(SOURCE) + "\t" + GpuCollector.IMAGE
                + "\tnone\ttrue\tfalse\tvolume:" + GpuCollector.VOLUME + ":/data:true;\t" + running
                + "\t\tprivate\t\tnull\t[\"ALL\"]\t[\"no-new-privileges\"]\tnull\t{}\tnull\tnull\t-1::0:gpu,:{};\tnull\ttrue"
                + "\t134217728\t250000000\t32\tunless-stopped\tNVIDIA_DRIVER_CAPABILITIES=utility;\n";
        }
        public GpuCollector.Response execute(String command, int maxBytes, long timeoutMs) {
            commands.add(command);
            assertTrue(maxBytes <= GpuCollector.MAX_TELEMETRY_BYTES);
            assertTrue(timeoutMs <= 150000);
            GpuCollector.Response response = responses.get(command);
            assertNotNull("Unexpected command: " + command, response);
            return response;
        }
        GpuCollector.Outcome collect(boolean enable) { return GpuCollector.collect(SOURCE, enable, sudo, this); }
    }

    @Test public void ordinaryRescanReadsOnlyValidatedExistingContainerAndVolume() {
        Fixture fixture = new Fixture(false);
        assertEquals(JSON, fixture.collect(false).json);
        assertEquals(List.of(GpuCollector.listContainer(false), GpuCollector.inspectContainer(SOURCE, false),
                GpuCollector.inspectVolume(false), GpuCollector.readTelemetry(SOURCE, false)), fixture.commands);
    }
    @Test public void ordinaryRescanDoesNotCreateOrStartAnything() {
        Fixture missing = new Fixture(false);
        missing.put(GpuCollector.listContainer(false), 0, "");
        GpuCollector.Outcome outcome = missing.collect(false);
        assertEquals(GpuCollector.Status.NOT_INSTALLED, outcome.status);
        assertTrue(outcome.json.contains("not-enabled"));
        assertEquals(List.of(GpuCollector.listContainer(false)), missing.commands);
        Fixture stopped = new Fixture(false);
        stopped.put(GpuCollector.inspectContainer(SOURCE, false), 0, stopped.inspection(false));
        assertEquals(GpuCollector.Status.STOPPED, stopped.collect(false).status);
        assertFalse(stopped.commands.contains(GpuCollector.startContainer(false)));
    }
    @Test public void approvedInstallationCreatesOnlyOwnedNamedResourcesAndPinnedImage() {
        Fixture fixture = new Fixture(true);
        fixture.put(GpuCollector.listContainer(true), 0, "");
        fixture.put(GpuCollector.listVolume(true), 0, "");
        fixture.put(GpuCollector.createVolume(true), 0, GpuCollector.VOLUME + "\n");
        fixture.put(GpuCollector.pullImage(true), 0, "fixture image pulled");
        fixture.put(GpuCollector.createContainer(SOURCE, true), 0, "a".repeat(64));
        assertEquals(GpuCollector.Status.READ, fixture.collect(true).status);
        String run = GpuCollector.createContainer(SOURCE, true);
        assertTrue(run.contains("--network none --read-only --cap-drop ALL --security-opt no-new-privileges"));
        assertTrue(run.contains("--gpus all --env NVIDIA_DRIVER_CAPABILITIES=utility"));
        assertTrue(run.contains("--mount type=volume,source=" + GpuCollector.VOLUME + ",target=/data"));
        assertTrue(run.contains("--label io.gridlens.collector-sha256=" + GpuCollector.hash(SOURCE)));
        assertFalse(run.contains("--privileged")); assertFalse(run.contains("--pid host"));
        assertFalse(run.contains("SYS_MODULE")); assertFalse(run.contains("docker.sock"));
        assertFalse(run.contains("type=bind")); assertFalse(run.contains("--publish"));
        assertTrue(fixture.commands.stream().allMatch(command -> command.startsWith("/usr/bin/sudo -S -p '' -- /usr/bin/docker ")));
    }
    @Test public void approvedRerunPreservesVolumeAndResumesOnlyOwnedStoppedRecorder() {
        Fixture fixture = new Fixture(false);
        fixture.put(GpuCollector.inspectContainer(SOURCE, false), 0, fixture.inspection(false));
        fixture.put(GpuCollector.startContainer(false), 0, GpuCollector.CONTAINER);
        assertEquals(GpuCollector.Status.READ, fixture.collect(true).status);
        assertTrue(fixture.commands.contains(GpuCollector.startContainer(false)));
        assertFalse(fixture.commands.contains(GpuCollector.createVolume(false)));
        assertFalse(fixture.commands.contains(GpuCollector.createContainer(SOURCE, false)));
        assertFalse(fixture.commands.stream().anyMatch(command -> command.contains(" rm ") || command.contains("remove")));
    }
    @Test public void unownedContainerOrVolumeIsNeverExecutedOrReplaced() {
        Fixture container = new Fixture(false);
        container.put(GpuCollector.inspectContainer(SOURCE, false), 0, container.inspection(true).replace("android\t", "unrelated\t"));
        assertEquals(GpuCollector.Status.OWNERSHIP_CONFLICT, container.collect(true).status);
        assertFalse(container.commands.contains(GpuCollector.readTelemetry(SOURCE, false)));
        Fixture volume = new Fixture(false);
        volume.put(GpuCollector.listContainer(false), 0, "");
        volume.put(GpuCollector.listVolume(false), 0, GpuCollector.VOLUME);
        volume.put(GpuCollector.inspectVolume(false), 0, "unrelated\tgpu-recorder\t1");
        assertEquals(GpuCollector.Status.OWNERSHIP_CONFLICT, volume.collect(true).status);
        assertFalse(volume.commands.contains(GpuCollector.createContainer(SOURCE, false)));
    }
    @Test public void changedSourceImageMountsOrPrivilegesFailClosedBeforeExec() {
        for (String field : List.of(GpuCollector.hash(SOURCE), GpuCollector.IMAGE, "\tnone\t", "\ttrue\tfalse\t", "volume:" + GpuCollector.VOLUME + ":/data:true;")) {
            Fixture fixture = new Fixture(false);
            fixture.put(GpuCollector.inspectContainer(SOURCE, false), 0, fixture.inspection(true).replace(field, "unsupported"));
            assertTrue(fixture.collect(true).status != GpuCollector.Status.READ);
            assertFalse(fixture.commands.contains(GpuCollector.readTelemetry(SOURCE, false)));
            assertFalse(fixture.commands.contains(GpuCollector.startContainer(false)));
        }
    }
    @Test public void localDriverBindOptionsAndHostNamespaceChangesAreRejected() {
        Fixture volume = new Fixture(false);
        volume.put(GpuCollector.inspectVolume(false), 0, "android\tgpu-recorder\t1\tlocal\tlocal\t{\"device\":\"/etc\",\"o\":\"bind\"}");
        assertEquals(GpuCollector.Status.OWNERSHIP_CONFLICT, volume.collect(true).status);
        assertFalse(volume.commands.contains(GpuCollector.readTelemetry(SOURCE, false)));
        int[] unsafeFields = {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27};
        for (int index : unsafeFields) {
            Fixture fixture = new Fixture(false);
            String[] parts = fixture.inspection(true).trim().split("\\t", -1);
            parts[index] = "unsafe-fixture";
            fixture.put(GpuCollector.inspectContainer(SOURCE, false), 0, String.join("\t", parts));
            assertEquals("field " + index, GpuCollector.Status.CONFIGURATION_MISMATCH, fixture.collect(true).status);
            assertFalse(fixture.commands.contains(GpuCollector.readTelemetry(SOURCE, false)));
        }
    }
    @Test public void startingRecorderWithoutFirstReportIsPendingRatherThanFailedSetup() {
        Fixture fixture = new Fixture(false);
        fixture.put(GpuCollector.inspectContainer(SOURCE, false), 0, fixture.inspection(false));
        fixture.put(GpuCollector.startContainer(false), 0, GpuCollector.CONTAINER);
        fixture.put(GpuCollector.readTelemetry(SOURCE, false), 1, "{\"schema\":1,\"collector\":{\"status\":\"unavailable\"},\"gpus\":[]}");
        assertEquals(GpuCollector.Status.STARTED, fixture.collect(true).status);
    }
    @Test public void failedPullNeverStartsContainerAndFailedReadCarriesNoRawOutput() {
        Fixture pull = new Fixture(false);
        pull.put(GpuCollector.listContainer(false), 0, "");
        pull.put(GpuCollector.listVolume(false), 0, GpuCollector.VOLUME);
        pull.put(GpuCollector.pullImage(false), 1, "sensitive upstream diagnostic fixture");
        GpuCollector.Outcome failed = pull.collect(true);
        assertEquals(GpuCollector.Status.INSTALL_FAILED, failed.status);
        assertFalse(failed.json.contains("diagnostic"));
        assertFalse(pull.commands.contains(GpuCollector.createContainer(SOURCE, false)));
        Fixture read = new Fixture(false);
        read.put(GpuCollector.readTelemetry(SOURCE, false), 1, "private-error-fixture");
        failed = read.collect(false);
        assertEquals(GpuCollector.Status.READ_FAILED, failed.status);
        assertFalse(failed.json.contains("private-error"));
    }
    @Test public void sourceIsOneQuotedArgumentAndNoUserInputEntersCommands() {
        assertEquals("'a'\\''b\n$HOME`id`'", GpuCollector.quote("a'b\n$HOME`id`"));
        assertTrue(GpuCollector.createContainer(SOURCE, false).endsWith("python -B -c " + GpuCollector.quote(SOURCE) + " run"));
        assertThrows(IllegalArgumentException.class, () -> GpuCollector.createContainer("x".repeat(GpuCollector.MAX_SOURCE_BYTES + 1), false));
        assertThrows(IllegalArgumentException.class, () -> GpuCollector.createContainer("x\0", false));
    }
}
