package com.gridlens.android;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Fixed Docker operations for the separately approved PC recorder. No host paths or shell input. */
final class GpuCollector {
    static final String CONTAINER = "gridlens-gpu-recorder";
    static final String VOLUME = "gridlens-gpu-recorder-data";
    static final String IMAGE = "python:3.13.11-slim-bookworm@sha256:20080e807bfc404f8450b185cf0fc95d553462673598549613735f70a5b4d5d0";
    static final int MAX_SOURCE_BYTES = 64 * 1024;
    static final int MAX_TELEMETRY_BYTES = 256 * 1024;
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
    static final class Outcome {
        final Status status;
        final String json;
        Outcome(Status status, String json) { this.status = status; this.json = json; }
    }

    static Outcome collect(String source, boolean enable, boolean sudo, Runner runner) {
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
            if (parts.length != 28 || !parts[0].equals("android") || !parts[1].equals("gpu-recorder")) return outcome(Status.OWNERSHIP_CONFLICT);
            if (!parts[2].equals("1") || !parts[3].equals(hash(source)) || !parts[4].equals(IMAGE)
                    || !parts[5].equals("none") || !parts[6].equals("true") || !parts[7].equals("false")
                    || !parts[8].equals("volume:" + VOLUME + ":/data:true;")
                    || !parts[10].isEmpty() || !parts[11].equals("private") || !parts[12].isEmpty()
                    || !emptyArray(parts[13]) || !parts[14].equals("[\"ALL\"]")
                    || !(parts[15].equals("[\"no-new-privileges\"]") || parts[15].equals("[\"no-new-privileges:true\"]"))
                    || !emptyArray(parts[16]) || !(parts[17].equals("null") || parts[17].equals("{}"))
                    || !emptyArray(parts[18]) || !emptyArray(parts[19]) || !(parts[20].equals("-1::0:gpu,:{};") || parts[20].equals("-1::0:gpu,:null;"))
                    || !emptyArray(parts[21]) || !parts[22].equals("true") || !parts[23].equals("134217728")
                    || !parts[24].equals("250000000") || !parts[25].equals("32") || !parts[26].equals("unless-stopped")
                    || !parts[27].equals("NVIDIA_DRIVER_CAPABILITIES=utility;")) return outcome(Status.CONFIGURATION_MISMATCH);
            Response ownedVolume = runner.execute(inspectVolume(sudo), 1024, 8000);
            if (!safeVolume(ownedVolume)) return outcome(Status.OWNERSHIP_CONFLICT);
            if (!parts[9].equals("true")) {
                if (!parts[9].equals("false")) return outcome(Status.CONFIGURATION_MISMATCH);
                if (!enable) return outcome(Status.STOPPED);
                if (runner.execute(startContainer(sudo), 1024, 15000).exit != 0) return outcome(Status.INSTALL_FAILED);
                newlyStarted = true;
            }
            Response read = runner.execute(readTelemetry(source, sudo), MAX_TELEMETRY_BYTES, 8000);
            String json = read.output.trim();
            // Complete schema validation also happens at the Android loopback storage boundary.
            if (read.exit != 0 || !json.startsWith("{") || !json.endsWith("}")
                    || json.getBytes(StandardCharsets.UTF_8).length > MAX_TELEMETRY_BYTES) return outcome(newlyStarted ? Status.STARTED : Status.READ_FAILED);
            return new Outcome(Status.READ, json);
        } catch (Exception error) { return outcome(enable ? Status.INSTALL_FAILED : Status.READ_FAILED); }
    }
    private static boolean emptyArray(String value) { return value.equals("null") || value.equals("[]"); }
    private static boolean safeVolume(Response response) {
        return response.exit == 0 && (response.output.trim().equals("android\tgpu-recorder\t1\tlocal\tlocal\tnull")
                || response.output.trim().equals("android\tgpu-recorder\t1\tlocal\tlocal\t{}"));
    }

    private static Outcome outcome(Status status) {
        String reported = status == Status.NOT_INSTALLED ? "not-enabled" : status == Status.INSTALL_FAILED ? "setup-failed" : "unavailable";
        return new Outcome(status, "{\"schema\":1,\"collector\":{\"status\":\"" + reported + "\"},\"gpus\":[]}");
    }
    private static String docker(boolean sudo) { return (sudo ? "/usr/bin/sudo -S -p '' -- " : "") + "/usr/bin/docker "; }
    private static String label(String key) { return "{{index .Labels \"" + key + "\"}}"; }
    private static String containerLabel(String key) { return "{{index .Config.Labels \"" + key + "\"}}"; }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    static String listContainer(boolean sudo) { return docker(sudo) + "container ls -a --filter 'name=^/" + CONTAINER + "$' --format '{{.Names}}'"; }
    static String listVolume(boolean sudo) { return docker(sudo) + "volume ls --filter 'name=^" + VOLUME + "$' --format '{{.Name}}'"; }
    static String inspectVolume(boolean sudo) { return docker(sudo) + "volume inspect --format " + quote(label(OWNER) + "\t" + label(COMPONENT) + "\t" + label(VERSION) + "\t{{.Driver}}\t{{.Scope}}\t{{json .Options}}") + " " + VOLUME; }
    static String createVolume(boolean sudo) { return docker(sudo) + "volume create --label " + OWNER + "=android --label " + COMPONENT + "=gpu-recorder --label " + VERSION + "=1 " + VOLUME; }
    static String pullImage(boolean sudo) { return docker(sudo) + "pull " + IMAGE; }
    static String inspectContainer(String source, boolean sudo) {
        validateSource(source);
        return docker(sudo) + "container inspect --format " + quote(containerLabel(OWNER) + "\t" + containerLabel(COMPONENT)
                + "\t" + containerLabel(VERSION) + "\t" + containerLabel(SOURCE_HASH) + "\t{{.Config.Image}}\t{{.HostConfig.NetworkMode}}"
                + "\t{{.HostConfig.ReadonlyRootfs}}\t{{.HostConfig.Privileged}}\t{{range .Mounts}}{{.Type}}:{{.Name}}:{{.Destination}}:{{.RW}};{{end}}\t{{.State.Running}}"
                + "\t{{.HostConfig.PidMode}}\t{{.HostConfig.IpcMode}}\t{{.HostConfig.UTSMode}}\t{{json .HostConfig.CapAdd}}\t{{json .HostConfig.CapDrop}}"
                + "\t{{json .HostConfig.SecurityOpt}}\t{{json .HostConfig.Binds}}\t{{json .HostConfig.PortBindings}}\t{{json .HostConfig.Devices}}\t{{json .HostConfig.DeviceCgroupRules}}"
                + "\t{{range .HostConfig.DeviceRequests}}{{.Count}}:{{.Driver}}:{{len .DeviceIDs}}:{{range .Capabilities}}{{range .}}{{.}},{{end}}{{end}}:{{json .Options}};{{end}}\t{{json .Config.Entrypoint}}"
                + "\t{{if and (eq (len .Config.Cmd) 5) (eq (index .Config.Cmd 0) \"python\") (eq (index .Config.Cmd 1) \"-B\") (eq (index .Config.Cmd 2) \"-c\")"
                + " (eq (index .Config.Cmd 3) " + goQuote(source) + ") (eq (index .Config.Cmd 4) \"run\")}}true{{else}}false{{end}}"
                + "\t{{.HostConfig.Memory}}\t{{.HostConfig.NanoCpus}}\t{{.HostConfig.PidsLimit}}\t{{.HostConfig.RestartPolicy.Name}}"
                + "\t{{range .Config.Env}}{{if and (ge (len .) 27) (eq (slice . 0 27) \"NVIDIA_DRIVER_CAPABILITIES=\")}}{{.}};{{end}}{{end}}") + " " + CONTAINER;
    }
    static String createContainer(String source, boolean sudo) {
        validateSource(source);
        return docker(sudo) + "run --detach --name " + CONTAINER + " --label " + OWNER + "=android --label " + COMPONENT
                + "=gpu-recorder --label " + VERSION + "=1 --label " + SOURCE_HASH + "=" + hash(source)
                + " --network none --read-only --cap-drop ALL --security-opt no-new-privileges --pids-limit 32 --memory 128m --cpus 0.25"
                + " --restart unless-stopped --gpus all --env NVIDIA_DRIVER_CAPABILITIES=utility --mount type=volume,source=" + VOLUME
                + ",target=/data " + IMAGE + " python -B -c " + quote(source) + " run";
    }
    static String startContainer(boolean sudo) { return docker(sudo) + "container start " + CONTAINER; }
    static String readTelemetry(String source, boolean sudo) { validateSource(source); return docker(sudo) + "exec " + CONTAINER + " python -B -c " + quote(source) + " read"; }
    static String hash(String source) {
        try {
            StringBuilder result = new StringBuilder(64);
            for (byte part : MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))) result.append(String.format(Locale.ROOT, "%02x", part & 255));
            return result.toString();
        } catch (java.security.GeneralSecurityException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static void validateSource(String source) {
        if (source == null || source.isEmpty() || source.indexOf('\0') >= 0 || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Bundled GPU recorder source unavailable");
    }
    private static String goQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    private GpuCollector() {}
}
