package com.gridlens.android;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native-only, explicitly authorized fixed rewards GET inside one pinned node. */
public final class RewardsReader {
    public static final int MAX_SOURCE_BYTES = 64 * 1024;
    public static final int MAX_RESULT_BYTES = 1024;
    public static final long MAX_RETRY_SECONDS = 2147483647L;
    private static final String[] CLEAN_EXEC_ENVIRONMENT = {
        "LD_PRELOAD=", "LD_AUDIT=", "LD_LIBRARY_PATH=", "GLIBC_TUNABLES=",
        "LD_DEBUG=", "LD_DEBUG_OUTPUT=", "LD_PROFILE=", "LD_PROFILE_OUTPUT=",
        "LD_ORIGIN_PATH=", "GCONV_PATH=", "LOCPATH=", "NLSPATH=", "LANG=C", "LC_ALL=C"
    };
    private static final String INSPECT = " inspect --format '{{.Id}}\t{{.State.Running}}\t{{.Config.Image}}' ";
    private static final Pattern RESULT = Pattern.compile("\\{\\\"schema\\\":1,\\\"address\\\":\\\"([1-9A-HJ-NP-Za-km-z]{32,44})\\\",\\\"observedAtMs\\\":([1-9][0-9]{0,15}),\\\"status\\\":\\\"(ok|authentication-failed|rate-limited|identity-mismatch|unavailable)\\\"(?:,\\\"amount\\\":\\\"((?:0|[1-9][0-9]{0,12})\\.[0-9]{6})\\\")?(?:,\\\"retryAfterSeconds\\\":(0|[1-9][0-9]{0,9}))?\\}");

    public enum Status {
        OK("ok"), AUTHENTICATION_FAILED("authentication-failed"), RATE_LIMITED("rate-limited"),
        IDENTITY_MISMATCH("identity-mismatch"), UNAVAILABLE("unavailable"), NOT_AUTHORIZED("unavailable");
        public final String wire;
        Status(String wire) { this.wire = wire; }
    }
    public static final class Binding {
        public final String runtime, wrapperId, nodeContainerId, address;
        public Binding(String runtime, String wrapperId, String nodeContainerId, String address) {
            if (!("docker".equals(runtime) || "podman".equals(runtime))
                    || nodeContainerId == null || !nodeContainerId.matches("[a-f0-9]{64}")
                    || !DiscoveryPolicy.publicAddress(address)) throw new IllegalArgumentException("Exact node binding required");
            if (wrapperId != null && wrapperId.isEmpty()) wrapperId = null;
            if (wrapperId != null && !wrapperId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Exact wrapper identity required");
            this.runtime = runtime; this.wrapperId = wrapperId; this.nodeContainerId = nodeContainerId; this.address = address;
        }
    }
    public static final class Response {
        public final int exit;
        public final String output;
        public Response(int exit, String output) { this.exit = exit; this.output = output; }
    }
    public interface Runner { Response execute(String command, int maxBytes, long timeoutMs) throws Exception; }
    public static final class Outcome {
        public final Status status;
        public final String json, amount;
        public final long observedAtMs, retryAfterSeconds;
        public final boolean suspendAuthorization;
        private Outcome(Status status, String json, String amount, long observedAtMs, long retryAfterSeconds) {
            this.status = status; this.json = json; this.amount = amount;
            this.observedAtMs = observedAtMs; this.retryAfterSeconds = retryAfterSeconds;
            suspendAuthorization = status == Status.AUTHENTICATION_FAILED || status == Status.IDENTITY_MISMATCH;
        }
    }

    public static Outcome collect(String source, Binding binding, boolean authorized, boolean sudo, Runner runner) {
        if (binding == null) throw new IllegalArgumentException("Exact node binding required");
        if (!authorized) return failure(Status.NOT_AUTHORIZED, binding.address);
        validateSource(source);
        try {
            if (binding.wrapperId != null) {
                Response wrapper = runner.execute(inspectWrapper(binding, sudo), 1024, 8000);
                if (!running(wrapper, binding.wrapperId, "podman")) return failure(Status.UNAVAILABLE, binding.address);
            }
            Response node = runner.execute(inspectNode(binding, sudo), 1024, 8000);
            if (!running(node, binding.nodeContainerId, "nosana-node")) return failure(Status.UNAVAILABLE, binding.address);
            Response identity = runner.execute(identityCommand(binding, sudo), 256, 8000);
            if (identity.exit != 0 || identity.output == null) return failure(Status.UNAVAILABLE, binding.address);
            if (!identity.output.trim().equals(binding.address)) return failure(Status.IDENTITY_MISMATCH, binding.address);
            Response read = runner.execute(readCommand(source, binding, sudo), MAX_RESULT_BYTES, 14000);
            if (read.exit != 0) return failure(Status.UNAVAILABLE, binding.address);
            return parse(read.output, binding.address, System.currentTimeMillis());
        } catch (Exception error) { return failure(Status.UNAVAILABLE, binding.address); }
    }

    private static boolean running(Response response, String id, String imageRepository) {
        if (response.exit != 0 || response.output == null) return false;
        String[] fields = response.output.trim().split("\\t", -1);
        return fields.length == 3 && fields[0].equals(id) && fields[1].equals("true")
                && DiscoveryPolicy.imageMatches(fields[2], imageRepository);
    }
    public static Outcome parse(String value, String address, long nowMs) {
        if (!DiscoveryPolicy.publicAddress(address)) throw new IllegalArgumentException("Public node identity required");
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) return failure(Status.UNAVAILABLE, address);
        String json = value.trim();
        Matcher match = RESULT.matcher(json);
        if (!match.matches() || !match.group(1).equals(address)) return failure(Status.UNAVAILABLE, address);
        try {
            long observed = Long.parseLong(match.group(2));
            Status status = Status.UNAVAILABLE;
            for (Status option : Status.values()) if (option != Status.NOT_AUTHORIZED && option.wire.equals(match.group(3))) status = option;
            String amount = match.group(4);
            if ((status == Status.OK) != (amount != null)) return failure(Status.UNAVAILABLE, address);
            if (amount != null && new BigInteger(amount.replace(".", "")).compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
                return failure(Status.UNAVAILABLE, address);
            long delay = match.group(5) == null ? 0 : Long.parseLong(match.group(5));
            if (delay > MAX_RETRY_SECONDS) return failure(Status.UNAVAILABLE, address);
            // A wrong PC clock cannot turn an actual authentication denial into
            // a retryable failure, or shorten a provider's explicit cooldown.
            if (observed <= 0 || observed > nowMs + 300000 || observed > 9007199254740991L)
                return failure(status == Status.OK ? Status.UNAVAILABLE : status, address, delay);
            return new Outcome(status, json, amount, observed, delay);
        } catch (RuntimeException error) { return failure(Status.UNAVAILABLE, address); }
    }
    private static Outcome failure(Status status, String address) {
        return failure(status, address, 0);
    }
    private static Outcome failure(Status status, String address, long delay) {
        long at = System.currentTimeMillis();
        return new Outcome(status, "{\"schema\":1,\"address\":\"" + address + "\",\"observedAtMs\":" + at + ",\"status\":\"" + status.wire + "\""
                + (delay > 0 ? ",\"retryAfterSeconds\":" + delay : "") + "}", null, at, delay);
    }
    private static String prefix(boolean sudo) { return sudo ? "/usr/bin/sudo -S -p '' -- " : ""; }
    private static String execEnvironment() {
        StringBuilder result = new StringBuilder();
        for (String value : CLEAN_EXEC_ENVIRONMENT) result.append(" --env ").append(quote(value));
        return result.toString();
    }
    private static String runtime(Binding binding, boolean sudo) {
        if (binding.wrapperId == null) return prefix(sudo) + "/usr/bin/" + binding.runtime;
        // Clear ELF-loader variables before starting even env/podman in the wrapper.
        return prefix(sudo) + "/usr/bin/docker exec" + execEnvironment() + " " + binding.wrapperId
                + " /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin LANG=C /usr/bin/podman";
    }
    static String inspectWrapper(Binding binding, boolean sudo) { return prefix(sudo) + "/usr/bin/docker" + INSPECT + binding.wrapperId; }
    static String inspectNode(Binding binding, boolean sudo) { return runtime(binding, sudo) + INSPECT + binding.nodeContainerId; }
    static String identityCommand(Binding binding, boolean sudo) {
        return runtime(binding, sudo) + " exec" + execEnvironment() + " " + binding.nodeContainerId
                + " /usr/bin/env -i /usr/local/bin/node -e " + quote(DiscoveryPolicy.API_READER);
    }
    static String readCommand(String source, Binding binding, boolean sudo) {
        validateSource(source);
        String prelude = "ulimit -S -c 0 && ulimit -H -c 0 && printf 0 > /proc/self/coredump_filter && exec /usr/local/bin/node -e \"$1\" -- --gridlens-approved-rewards \"$2\"";
        return runtime(binding, sudo) + " exec" + execEnvironment() + " " + binding.nodeContainerId
                + " /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin LANG=C /bin/sh -c " + quote(prelude)
                + " gridlens-approved-rewards " + quote(source) + " " + binding.address;
    }
    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static void validateSource(String source) {
        if (source == null || source.isEmpty() || source.indexOf('\0') >= 0 || source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new IllegalArgumentException("Bundled rewards reader unavailable");
    }
    private RewardsReader() { }
}
