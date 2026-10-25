package com.gridlens.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Fixed Docker operations for the separately approved PC recorder. No host paths or shell input. */
final class NodeGpuCollector {
    final String CONTAINER;
    final String VOLUME;
    final NodeBinding binding;
    final String networkContainerId;
    final java.util.List<String> gpuUuids;
    final Long sinceAtMs;
    static final String IMAGE = "python:3.13.11-slim-bookworm@sha256:20080e807bfc404f8450b185cf0fc95d553462673598549613735f70a5b4d5d0";
    static final int MAX_SOURCE_BYTES = 64 * 1024;
    static final int MAX_TELEMETRY_BYTES = 1024 * 1024;
    private static final String OWNER = "io.gridlens.owner";
    private static final String COMPONENT = "io.gridlens.component";
    private static final String VERSION = "io.gridlens.collector-version";
    private static final String SOURCE_HASH = "io.gridlens.collector-sha256";

    enum Status {
        READ("GPU recorder report read"), NOT_INSTALLED("GPU recorder not enabled"),
        STARTED("GPU recorder started; first report pending"),
        STOPPED("GPU recorder stopped"), UNAVAILABLE("Docker GPU recorder unavailable"),
        ACCESS_REQUIRED("GPU recorder needs approved Docker access"),
        OWNERSHIP_CONFLICT("GPU recorder name belongs to an unverified resource"),
        CONFIGURATION_MISMATCH("GPU recorder configuration needs review"),
        INSTALL_FAILED("GPU recorder could not be enabled"), READ_FAILED("GPU telemetry could not be read");
        final String label;
        Status(String label) { this.label = label; }
    }
    static final class Response {
        final int exit;
        final String output;
        Response(int exit, String output) { this.exit = exit; this.output = output; }
    }
    interface Runner { Response execute(String command, int maxBytes, long timeoutMs) throws Exception; }
    interface BindingVerifier { boolean verify() throws Exception; }
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> REPAIR_ATTEMPTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REPAIR_COOLDOWN_NS = 600000L * 1000000L;
    static final class Outcome {
        final Status status;
        final String json;
        Outcome(Status status, String json) { this.status = status; this.json = json; }
    }

    Outcome collect(String source, boolean enable, boolean sudo, Runner runner) {
        return collect(source, enable, sudo, runner, () -> false);
    }
    Outcome collect(String source, boolean enable, boolean sudo, Runner runner, BindingVerifier verifier) {
        validateSource(source);
        try {
            boolean newlyStarted = false;
            Response listed = runner.execute(listContainer(sudo), 256, 8000);
            if (listed.exit != 0) return outcome(listed.exit == 127 ? Status.UNAVAILABLE : Status.ACCESS_REQUIRED);
            String container = listed.output.trim();
            if (!container.isEmpty() && !container.equals(CONTAINER)) return outcome(Status.OWNERSHIP_CONFLICT);
            if (container.isEmpty()) {
                if (!enable) return outcome(Status.NOT_INSTALLED);
                Response volumes = runner.execute(listVolume(sudo), 256, 8000);
                if (volumes.exit != 0) return outcome(Status.INSTALL_FAILED);
                String volume = volumes.output.trim();
                if (!volume.isEmpty() && !volume.equals(VOLUME)) return outcome(Status.OWNERSHIP_CONFLICT);
                if (volume.isEmpty()) {
                    Response created = runner.execute(createVolume(sudo), 1024, 8000);
                    if (created.exit != 0 || !created.output.trim().equals(VOLUME)) return outcome(Status.INSTALL_FAILED);
                }
                Response ownedVolume = runner.execute(inspectVolume(sudo), 1024, 8000);
                if (!safeVolume(ownedVolume))
                    return outcome(Status.OWNERSHIP_CONFLICT);
                if (runner.execute(pullImage(sudo), 64 * 1024, 150000).exit != 0) return outcome(Status.INSTALL_FAILED);
                if (runner.execute(createContainer(source, sudo), 1024, 15000).exit != 0) return outcome(Status.INSTALL_FAILED);
                newlyStarted = true;
            }
            Response inspected = runner.execute(inspectContainer(source, sudo), 4096, 8000);
            if (inspected.exit != 0) return outcome(Status.UNAVAILABLE);
            String[] parts = inspected.output.trim().split("\\t", -1);
            if (parts.length != 29 || !parts[0].equals("android") || !parts[1].equals("node-gpu-recorder") || !parts[28].matches("[a-f0-9]{64}")) return outcome(Status.OWNERSHIP_CONFLICT);
            String ownedId = parts[28];
            if (!parts[2].equals("1") || !parts[3].equals(hash(source)) || !parts[4].equals(IMAGE)
                    || !parts[5].equals("container:" + networkContainerId) || !parts[6].equals("true") || !parts[7].equals("false")
                    || !parts[8].equals("volume:" + VOLUME + ":/data:true;")
                    || !parts[10].isEmpty() || !parts[11].equals("private") || !parts[12].isEmpty()
                    || !emptyArray(parts[13]) || !parts[14].equals("[\"ALL\"]")
                    || !(parts[15].equals("[\"no-new-privileges\"]") || parts[15].equals("[\"no-new-privileges:true\"]"))
                    || !emptyArray(parts[16]) || !(parts[17].equals("null") || parts[17].equals("{}"))
                    || !emptyArray(parts[18]) || !emptyArray(parts[19]) || !(parts[20].equals("0::" + String.join(",", gpuUuids) + ",:gpu,:{};") || parts[20].equals("0::" + String.join(",", gpuUuids) + ",:gpu,:null;"))
                    || !emptyArray(parts[21]) || !parts[22].equals("true") || !parts[23].equals("134217728")
                    || !parts[24].equals("250000000") || !parts[25].equals("32") || !parts[26].equals("unless-stopped")
                    || !parts[27].equals("NVIDIA_DRIVER_CAPABILITIES=utility;")) return outcome(Status.CONFIGURATION_MISMATCH);
            Response ownedVolume = runner.execute(inspectVolume(sudo), 1024, 8000);
            if (!safeVolume(ownedVolume)) return outcome(Status.OWNERSHIP_CONFLICT);
            if (!parts[9].equals("true")) {
                if (!parts[9].equals("false")) return outcome(Status.CONFIGURATION_MISMATCH);
                if (!enable) return outcome(Status.STOPPED);
                if (runner.execute(startContainer(sudo, ownedId), 1024, 15000).exit != 0) return outcome(Status.INSTALL_FAILED);
                newlyStarted = true;
            }
            if (parts[9].equals("true")) {
                Response targetNs = runner.execute(targetNamespace(sudo), 128, 8000);
                Response ownedNs = runner.execute(recorderNamespace(sudo, ownedId), 128, 8000);
                String target = namespace(targetNs), owned = namespace(ownedNs);
                if (target != null && owned != null && !target.equals(owned)) {
                    long now = System.nanoTime();
                    Long previousAttempt = REPAIR_ATTEMPTS.get(CONTAINER);
                    if (previousAttempt != null && now - previousAttempt < REPAIR_COOLDOWN_NS) return outcome(Status.READ_FAILED);
                    if (!verifier.verify()) return outcome(Status.CONFIGURATION_MISMATCH);
                    Response stillRunning = runner.execute(runningCommand(sudo, ownedId), 32, 8000);
                    if (stillRunning.exit != 0 || !stillRunning.output.trim().equals("true")) return outcome(Status.STOPPED);
                    synchronized (REPAIR_ATTEMPTS) {
                        Long previous = REPAIR_ATTEMPTS.get(CONTAINER);
                        if (previous != null && now - previous < REPAIR_COOLDOWN_NS) return outcome(Status.READ_FAILED);
                        REPAIR_ATTEMPTS.entrySet().removeIf(entry -> now - entry.getValue() >= REPAIR_COOLDOWN_NS);
                        REPAIR_ATTEMPTS.put(CONTAINER, now);
                    }
                    // This is the already validated GridLens recorder, never the bound workload.
                    Response repaired = runner.execute(restartContainer(sudo, ownedId), 1024, 15000);
                    if (repaired.exit != 0 || !repaired.output.trim().equals(ownedId)) return outcome(Status.READ_FAILED);
                    String repairedNs = namespace(runner.execute(recorderNamespace(sudo, ownedId), 128, 8000));
                    String currentTarget = namespace(runner.execute(targetNamespace(sudo), 128, 8000));
                    if (!target.equals(repairedNs) || !target.equals(currentTarget)) return outcome(Status.READ_FAILED);
                    newlyStarted = true;
                }
            }
            Response read = runner.execute(readTelemetry(source, sudo, ownedId), MAX_TELEMETRY_BYTES, 8000);
            String json = read.output.trim();
            // Complete schema validation also happens at the Android loopback storage boundary.
            if (read.exit != 0 || !json.startsWith("{") || !json.endsWith("}")
                    || json.getBytes(StandardCharsets.UTF_8).length > MAX_TELEMETRY_BYTES) return outcome(newlyStarted ? Status.STARTED : Status.READ_FAILED);
            return new Outcome(Status.READ, json);
        } catch (Exception error) { return outcome(enable ? Status.INSTALL_FAILED : Status.READ_FAILED); }
    }
    private String namespace(Response response) {
        if (response.exit != 0) return null;
        String value = response.output.trim();
        return value.matches("net:\\[[0-9]{1,20}\\]") ? value : null;
    }
    private boolean emptyArray(String value) { return value.equals("null") || value.equals("[]"); }
    private boolean safeVolume(Response response) {
        return response.exit == 0 && (response.output.trim().equals("android\tnode-gpu-recorder\t1\tlocal\tlocal\tnull")
                || response.output.trim().equals("android\tnode-gpu-recorder\t1\tlocal\tlocal\t{}"));
    }

    private Outcome outcome(Status status) {
        String reported = status == Status.NOT_INSTALLED ? "not-enabled" : status == Status.INSTALL_FAILED ? "setup-failed" : "unavailable";
        return new Outcome(status, unavailable(binding, status == Status.STARTED ? "starting" : reported));
    }
    private String docker(boolean sudo) { return (sudo ? "/usr/bin/sudo -S -p '' -- " : "") + "/usr/bin/docker "; }
    private String label(String key) { return "{{index .Labels \"" + key + "\"}}"; }
    private String containerLabel(String key) { return "{{index .Config.Labels \"" + key + "\"}}"; }
    String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    String listContainer(boolean sudo) { return docker(sudo) + "container ls -a --filter 'name=^/" + CONTAINER + "$' --format '{{.Names}}'"; }
    String listVolume(boolean sudo) { return docker(sudo) + "volume ls --filter 'name=^" + VOLUME + "$' --format '{{.Name}}'"; }
    String inspectVolume(boolean sudo) { return docker(sudo) + "volume inspect --format " + quote(label(OWNER) + "\t" + label(COMPONENT) + "\t" + label(VERSION) + "\t{{.Driver}}\t{{.Scope}}\t{{json .Options}}") + " " + VOLUME; }
    String createVolume(boolean sudo) { return docker(sudo) + "volume create --label " + OWNER + "=android --label " + COMPONENT + "=node-gpu-recorder --label " + VERSION + "=1 " + VOLUME; }
    String pullImage(boolean sudo) { return docker(sudo) + "pull " + IMAGE; }
    String inspectContainer(String source, boolean sudo) {
        validateSource(source);
        return docker(sudo) + "container inspect --format " + quote(containerLabel(OWNER) + "\t" + containerLabel(COMPONENT)
                + "\t" + containerLabel(VERSION) + "\t" + containerLabel(SOURCE_HASH) + "\t{{.Config.Image}}\t{{.HostConfig.NetworkMode}}"
                + "\t{{.HostConfig.ReadonlyRootfs}}\t{{.HostConfig.Privileged}}\t{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}}:{{.RW}};{{end}}\t{{.State.Running}}"
                + "\t{{.HostConfig.PidMode}}\t{{.HostConfig.IpcMode}}\t{{.HostConfig.UTSMode}}\t{{json .HostConfig.CapAdd}}\t{{json .HostConfig.CapDrop}}"
                + "\t{{json .HostConfig.SecurityOpt}}\t{{json .HostConfig.Binds}}\t{{json .HostConfig.PortBindings}}\t{{json .HostConfig.Devices}}\t{{json .HostConfig.DeviceCgroupRules}}"
                + "\t{{range .HostConfig.DeviceRequests}}{{.Count}}:{{.Driver}}:{{range .DeviceIDs}}{{.}},{{end}}:{{range .Capabilities}}{{range .}}{{.}},{{end}}{{end}}:{{json .Options}};{{end}}\t{{json .Config.Entrypoint}}"
                + "\t{{if and (eq (len .Config.Cmd) 5) (eq (index .Config.Cmd 0) \"python\") (eq (index .Config.Cmd 1) \"-B\") (eq (index .Config.Cmd 2) \"-c\")"
                + " (eq (index .Config.Cmd 3) " + goQuote(source) + ") (eq (index .Config.Cmd 4) \"run\")}}true{{else}}false{{end}}"
                + "\t{{.HostConfig.Memory}}\t{{.HostConfig.NanoCpus}}\t{{.HostConfig.PidsLimit}}\t{{.HostConfig.RestartPolicy.Name}}"
                + "\t{{range .Config.Env}}{{if and (ge (len .) 27) (eq (slice . 0 27) \"NVIDIA_DRIVER_CAPABILITIES=\")}}{{.}};{{end}}{{end}}\t{{.Id}}") + " " + CONTAINER;
    }
    String createContainer(String source, boolean sudo) {
        validateSource(source);
        return docker(sudo) + "run --detach --name " + CONTAINER + " --label " + OWNER + "=android --label " + COMPONENT
                + "=node-gpu-recorder --label " + VERSION + "=1 --label " + SOURCE_HASH + "=" + hash(source)
                + " --network container:" + networkContainerId + " --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 32 --memory 128m --cpus 0.25"
                + " --restart unless-stopped --gpus " + quote("\"device=" + String.join(",", gpuUuids) + "\"") + " --env NVIDIA_DRIVER_CAPABILITIES=utility --mount type=volume,source=" + VOLUME
                + ",target=/data " + IMAGE + " python -B -c " + quote(source) + " run";
    }
    String startContainer(boolean sudo) { return docker(sudo) + "container start " + CONTAINER; }
    String startContainer(boolean sudo, String id) { return docker(sudo) + "container start " + ownedId(id); }
    String restartContainer(boolean sudo) { return docker(sudo) + "container restart --time 5 " + CONTAINER; }
    String restartContainer(boolean sudo, String id) { return docker(sudo) + "container restart --time 5 " + ownedId(id); }
    String runningCommand(boolean sudo, String id) { return docker(sudo) + "container inspect --format '{{.State.Running}}' " + ownedId(id); }
    String targetNamespace(boolean sudo) { return docker(sudo) + "exec " + networkContainerId + " /usr/bin/readlink /proc/self/ns/net"; }
    String recorderNamespace(boolean sudo) { return docker(sudo) + "exec " + CONTAINER + " python -B -c 'import os;print(os.readlink(\"/proc/self/ns/net\"))'"; }
    String recorderNamespace(boolean sudo, String id) { return docker(sudo) + "exec " + ownedId(id) + " python -B -c 'import os;print(os.readlink(\"/proc/self/ns/net\"))'"; }
    String readTelemetry(String source, boolean sudo) { validateSource(source); return docker(sudo) + "exec " + CONTAINER + " python -B -c " + quote(source) + " read" + (sinceAtMs == null ? "" : " " + sinceAtMs); }
    String readTelemetry(String source, boolean sudo, String id) { validateSource(source); return docker(sudo) + "exec " + ownedId(id) + " python -B -c " + quote(source) + " read" + (sinceAtMs == null ? "" : " " + sinceAtMs); }
    private String ownedId(String id) { if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Verified recorder identity required"); return id; }
    String hash(String source) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte part : MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private void validateSource(String source) {
        if (source == null || source.isEmpty() || source.indexOf('\0') >= 0 || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Bundled GPU recorder source unavailable");
    }
    private String goQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    NodeGpuCollector(NodeBinding binding, String networkContainerId, java.util.List<String> gpuUuids, Long sinceAtMs) {
        if (binding == null || networkContainerId == null || !networkContainerId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Verified node namespace required");
        this.binding = binding; this.networkContainerId = networkContainerId;
        this.gpuUuids = java.util.Collections.unmodifiableList(parseGpuUuids(String.join("\n", gpuUuids)));
        if (this.gpuUuids.isEmpty() || sinceAtMs != null && (sinceAtMs < 0 || sinceAtMs > 9007199254740991L)) throw new IllegalArgumentException("Invalid node GPU binding");
        this.sinceAtMs = sinceAtMs;
        String suffix = hash(binding.key() + ":" + binding.address).substring(0, 24);
        CONTAINER = "gridlens-node-gpu-" + suffix;
        VOLUME = CONTAINER + "-data";
    }
    String boundSource(String source) {
        validateSource(source);
        return "BOUND_ADDRESS = '" + binding.address + "'\nBOUND_NODE_ID = '" + binding.nodeContainerId
            + "'\nBOUND_UUIDS = ['" + String.join("','", gpuUuids) + "']\n" + source;
    }
    static java.util.List<String> parseGpuUuids(String raw) {
        if (raw == null || raw.length() > 8192) throw new IllegalArgumentException("Invalid GPU identity response");
        java.util.TreeSet<String> uuids = new java.util.TreeSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            if (!value.matches("GPU-[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid GPU identity");
            value = "GPU-" + value.substring(4).toLowerCase(java.util.Locale.ROOT);
            if (!uuids.add(value) || uuids.size() > 64) throw new IllegalArgumentException("Ambiguous GPU identity");
        }
        return new java.util.ArrayList<>(uuids);
    }
    static String unavailable(NodeBinding binding, String status) {
        if (!java.util.Arrays.asList("unavailable", "binding-mismatch", "not-enabled", "setup-failed", "starting", "unsupported").contains(status)) status = "unavailable";
        long now = System.currentTimeMillis();
        return "{\"schema\":2,\"address\":\"" + binding.address + "\",\"observedAtMs\":null,\"status\":\"" + status
            + "\",\"gpuUuids\":[],\"intervalSeconds\":15,\"fromAtMs\":" + Math.max(0, now - 86400000L)
            + ",\"toAtMs\":" + now + ",\"truncated\":false,\"clockTrusted\":false,\"samples\":[]}";
    }
}
