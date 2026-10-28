package com.gridlens.android;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-side public egress lookup. No wallet, phone-IP lookup or configurable URL. */
final class PublicIpReader {
    static final String SOURCE = "pc-egress-ipify";
    static final String LEGACY_SOURCE = "node-egress-ipify";
    static final int MAX_BODY_BYTES = 128, MAX_OUTPUT_BYTES = 16384;
    static final int NETWORK_FAILURE = 7, REQUEST_TIMEOUT = 28, HTTP_FAILURE = 22;
    static final long MAX_SAFE_INTEGER = 9007199254740991L;
    private static final String PUBLIC_FAILURES = "missing-curl|dns|connection|timeout|tls|http-refusal|invalid-response";
    private static final Pattern STORED = Pattern.compile("\\{\"schema\":1,\"observedAtMs\":([1-9][0-9]{0,15}),\"status\":\"(ok|unavailable)\","
            + "\"publicIp\":(null|\"[0-9a-fA-F:.]{1,45}\"),\"publicObservedAtMs\":(null|[1-9][0-9]{0,15}),"
            + "\"privateIp\":(null|\"[0-9a-fA-F:.]{1,45}\"),\"privateObservedAtMs\":(null|[1-9][0-9]{0,15}),"
            + "\"source\":\"(node-egress-ipify|pc-egress-ipify)\""
            + "(?:,\"publicFailure\":\"(" + PUBLIC_FAILURES + ")\")?"
            + "(?:,\"privateFailure\":\"(interface-read-failure)\")?"
            + "(?:,\"httpStatus\":([1-5][0-9]{2}))?(?:,\"retryAtMs\":([1-9][0-9]{0,15}))?\\}");

    static final class Response {
        final int exit;
        final String output;
        Response(int exit, String output) { this.exit = exit; this.output = output; }
    }
    interface Runner { Response execute(String command, int maxBytes, long timeoutMs) throws Exception; }
    interface Clock { long now(); }
    static final class Observation {
        final long observedAtMs;
        final long retryAtMs;
        final String publicIp, privateIp, source, publicFailure, privateFailure;
        final Integer httpStatus;
        final Long publicObservedAtMs, privateObservedAtMs;
        Observation(long observedAtMs, String publicIp, Long publicObservedAtMs, String privateIp, Long privateObservedAtMs) {
            this(observedAtMs, publicIp, publicObservedAtMs, privateIp, privateObservedAtMs, 0);
        }
        Observation(long observedAtMs, String publicIp, Long publicObservedAtMs, String privateIp, Long privateObservedAtMs, long retryAtMs) {
            this(observedAtMs, publicIp, publicObservedAtMs, privateIp, privateObservedAtMs, retryAtMs, SOURCE, null, null, null);
        }
        Observation(long observedAtMs, String publicIp, Long publicObservedAtMs, String privateIp, Long privateObservedAtMs,
                long retryAtMs, String source, String publicFailure, String privateFailure, Integer httpStatus) {
            if (!SOURCE.equals(source) && !LEGACY_SOURCE.equals(source)
                    || publicFailure != null && !publicFailure.matches(PUBLIC_FAILURES)
                    || privateFailure != null && !privateFailure.equals("interface-read-failure")
                    || httpStatus != null && (httpStatus < 100 || httpStatus > 599)
                    || publicIp != null && publicFailure != null || privateIp != null && privateFailure != null)
                throw new IllegalArgumentException("Invalid network source diagnostic");
            if (observedAtMs <= 0 || observedAtMs > MAX_SAFE_INTEGER
                    || (publicIp == null) != (publicObservedAtMs == null) || (privateIp == null) != (privateObservedAtMs == null)
                    || publicObservedAtMs != null && (publicObservedAtMs < 1 || publicObservedAtMs > observedAtMs)
                    || privateObservedAtMs != null && (privateObservedAtMs < 1 || privateObservedAtMs > observedAtMs)
                    || retryAtMs < 0 || retryAtMs > MAX_SAFE_INTEGER)
                throw new IllegalArgumentException("Invalid network observation time");
            this.publicIp = publicIp == null ? null : publicAddress(publicIp);
            this.privateIp = privateIp == null ? null : privateAddress(privateIp);
            if (publicIp != null && this.publicIp == null || privateIp != null && this.privateIp == null)
                throw new IllegalArgumentException("Invalid network observation address");
            this.observedAtMs = observedAtMs; this.publicObservedAtMs = publicObservedAtMs; this.privateObservedAtMs = privateObservedAtMs;
            this.retryAtMs = retryAtMs; this.source = source; this.publicFailure = publicFailure;
            this.privateFailure = privateFailure; this.httpStatus = httpStatus;
        }
        String json() {
            return "{\"schema\":1,\"observedAtMs\":" + observedAtMs + ",\"status\":\"" + (publicIp == null ? "unavailable" : "ok")
                    + "\",\"publicIp\":" + quoted(publicIp) + ",\"publicObservedAtMs\":" + publicObservedAtMs
                    + ",\"privateIp\":" + quoted(privateIp) + ",\"privateObservedAtMs\":" + privateObservedAtMs + ",\"source\":\"" + source + "\""
                    + (publicFailure == null ? "" : ",\"publicFailure\":" + quoted(publicFailure))
                    + (privateFailure == null ? "" : ",\"privateFailure\":" + quoted(privateFailure))
                    + (httpStatus == null ? "" : ",\"httpStatus\":" + httpStatus)
                    + (retryAtMs <= 0 ? "" : ",\"retryAtMs\":" + retryAtMs) + "}";
        }
    }
    static Observation unavailable(long at) { return new Observation(at, null, null, null, null); }
    static Observation parse(String value) {
        if (value == null || value.length() > 1024) return null;
        Matcher match = STORED.matcher(value);
        if (!match.matches()) return null;
        try {
            Observation result = new Observation(Long.parseLong(match.group(1)), unquote(match.group(3)), time(match.group(4)), unquote(match.group(5)), time(match.group(6)),
                    match.group(11) == null ? 0 : Long.parseLong(match.group(11)), match.group(7), match.group(8), match.group(9),
                    match.group(10) == null ? null : Integer.valueOf(match.group(10)));
            if ((result.publicIp != null) != match.group(2).equals("ok")) return null;
            return result;
        } catch (RuntimeException invalid) { return null; }
    }
    private static Long time(String value) { return value.equals("null") ? null : Long.parseLong(value); }
    private static String unquote(String value) { return value.equals("null") ? null : value.substring(1, value.length() - 1); }
    private static String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }

    /** Compatibility overload; node identity and sudo never influence PC interface/egress reads. */
    static Observation read(NodeBinding unused, String sshAddress, boolean unusedSudo, Runner runner, Clock clock) {
        return read(sshAddress, runner, clock);
    }
    static Observation read(String sshAddress, Runner runner, Clock clock) {
        String internal = null, external = null, privateFailure = null, publicFailure = null;
        Long privateAt = null, publicAt = null; Integer httpStatus = null;
        long retryAtMs = 0;
        try {
            Response interfaces = runner.execute(PcMetadata.ADDRESSES_COMMAND, PcMetadata.MAX_RESPONSE_BYTES, 8000);
            if (interfaces.exit == 0 && interfaces.output != null && interfaces.output.getBytes(StandardCharsets.UTF_8).length <= PcMetadata.MAX_RESPONSE_BYTES) {
                internal = selectPrivate(interfaces.output, sshAddress);
                if (internal != null) privateAt = clock.now();
            } else privateFailure = "interface-read-failure";
        } catch (Exception unavailable) { privateFailure = "interface-read-failure"; }
        try {
            int family = 4;
            Response response = runner.execute(command(family), MAX_OUTPUT_BYTES, 11000);
            HttpResult parsed = http(response);
            // Only a transport failure without HTTP evidence can try the other family.
            // The SSH runner checks cancellation and approval again before every command.
            if (parsed.status == null && fallback(response.exit)) {
                family = 6;
                response = runner.execute(command(family), MAX_OUTPUT_BYTES, 11000);
                parsed = http(response);
            }
            httpStatus = parsed.status;
            if (parsed.retryAfter != null) retryAtMs = retryDeadline(parsed.retryAfter, clock.now());
            if (httpStatus != null && httpStatus != 200) {
                publicFailure = "http-refusal";
            } else if (response.exit != 0) publicFailure = failure(response.exit);
            else if (httpStatus == null || parsed.body == null || parsed.body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES
                    || (external = publicAddress(parsed.body.trim())) == null || (external.indexOf(':') >= 0) != (family == 6)) {
                external = null; publicFailure = "invalid-response";
            } else publicAt = clock.now();
        } catch (Exception unavailable) {
            publicFailure = unavailable.getMessage() != null && unavailable.getMessage().equals("Inventory timed out") ? "timeout" : "connection";
        }
        long at = clock.now();
        if (privateAt != null && privateAt > at) { internal = null; privateAt = null; privateFailure = "interface-read-failure"; }
        if (publicAt != null && publicAt > at) { external = null; publicAt = null; publicFailure = "invalid-response"; }
        return new Observation(at, external, publicAt, internal, privateAt, retryAtMs, SOURCE, publicFailure, privateFailure, "http-refusal".equals(publicFailure) ? httpStatus : null);
    }
    static String command(NodeBinding unused, boolean unusedSudo) { return command(4); }
    static String command(NodeBinding unused, boolean unusedSudo, int family) { return command(family); }
    static String command(int family) {
        if (family != 4 && family != 6) throw new IllegalArgumentException("Fixed IP family required");
        return "if command -v curl >/dev/null 2>&1; then curl -q -" + family
                + " --silent --show-error --connect-timeout 10 --max-time 10 --max-filesize 128 --proto '=https' --max-redirs 0 --include https://"
                + (family == 4 ? "api.ipify.org" : "api6.ipify.org") + "; else exit 127; fi";
    }
    private static boolean fallback(int exit) { return exit == 5 || exit == 6 || exit == 7 || exit == 28; }
    private static String failure(int exit) {
        if (exit == 127) return "missing-curl";
        if (exit == 5 || exit == 6) return "dns";
        if (exit == 28) return "timeout";
        if (exit == 35 || exit == 51 || exit == 58 || exit == 59 || exit == 60 || exit == 77 || exit == 80 || exit == 82 || exit == 83 || exit == 90 || exit == 91) return "tls";
        if (exit == 63 || exit == 23 || exit == 3) return "invalid-response";
        return "connection";
    }
    private static final class HttpResult {
        Integer status; String retryAfter, body;
    }
    private static HttpResult http(Response response) {
        HttpResult result = new HttpResult();
        if (response.output == null || response.output.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) return result;
        String remaining = response.output.replace("\r\n", "\n");
        while (remaining.startsWith("HTTP/")) {
            int end = remaining.indexOf("\n\n");
            if (end < 0) return result;
            String[] lines = remaining.substring(0, end).split("\n");
            Matcher status = Pattern.compile("HTTP/(?:1\\.[01]|2|3) ([1-5][0-9]{2})(?: .*)?").matcher(lines[0]);
            if (!status.matches()) return result;
            result.status = Integer.valueOf(status.group(1)); result.retryAfter = null;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && lines[i].substring(0, colon).equalsIgnoreCase("retry-after")) {
                    String value = lines[i].substring(colon + 1).trim();
                    if (value.length() <= 128) result.retryAfter = value;
                }
            }
            remaining = remaining.substring(end + 2);
        }
        result.body = remaining;
        return result;
    }
    private static long retryDeadline(String value, long now) {
        if (value == null || value.length() > 128) return 0;
        try {
            if (value.matches("[0-9]{1,10}")) {
                long seconds = Long.parseLong(value);
                if (seconds > 2147483647L) return 0;
                return Math.min(MAX_SAFE_INTEGER, now + seconds * 1000L);
            }
            if (value.matches("(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun), [0-9]{2} (?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) {
                long at = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                return at > now && at <= MAX_SAFE_INTEGER ? at : 0;
            }
        } catch (RuntimeException invalid) { }
        return 0;
    }
    static String selectPrivate(String raw, String sshAddress) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > PcMetadata.MAX_RESPONSE_BYTES) return null;
        List<String> addresses = new ArrayList<>();
        for (String item : raw.trim().split("\\s+")) {
            String valid = privateAddress(item);
            if (valid != null && !addresses.contains(valid)) addresses.add(valid);
            if (addresses.size() == PcMetadata.MAX_IP_ADDRESSES) break;
        }
        String target = privateAddress(sshAddress);
        if (target != null && addresses.contains(target)) return target;
        for (String address : addresses) if (address.indexOf(':') < 0) return address;
        return addresses.isEmpty() ? null : addresses.get(0);
    }
    static String privateAddress(String raw) {
        InetAddress value = literal(raw);
        if (value == null) return null;
        byte[] bytes = value.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        boolean privateIp = value instanceof Inet4Address ? a == 10 || a == 172 && b >= 16 && b <= 31 || a == 192 && b == 168 || a == 100 && b >= 64 && b <= 127 : (a & 254) == 252;
        return privateIp ? value.getHostAddress() : null;
    }
    static String publicAddress(String raw) {
        InetAddress value = literal(raw);
        if (value == null || privateAddress(raw) != null) return null;
        byte[] bytes = value.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (value instanceof Inet4Address) {
            int c = bytes[2] & 255;
            if (a == 0 || a == 127 || a >= 224 || a == 169 && b == 254
                    || a == 192 && (b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100) || a == 203 && b == 0 && c == 113) return null;
        } else {
            int prefix = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
            if ((a & 224) != 32 || a == 32 && b == 1 && (prefix == 0x0db8 || prefix == 2 || prefix >= 0x10 && prefix <= 0x2f)) return null;
        }
        return value.getHostAddress();
    }
    private static InetAddress literal(String raw) {
        if (raw == null || raw.length() > 45 || !raw.matches("[0-9a-fA-F:.]+")) return null;
        try { return DiscoveryPolicy.numericAddress(raw); } catch (IllegalArgumentException invalid) { return null; }
    }
    private PublicIpReader() {}
}
