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
    static final String SOURCE = "node-egress-ipify";
    static final int MAX_OUTPUT_BYTES = 128;
    static final int NETWORK_FAILURE = 10, REQUEST_TIMEOUT = 11, HTTP_FAILURE = 12;
    static final long MAX_SAFE_INTEGER = 9007199254740991L;
    static final String SCRIPT = "const f=process.argv[1];if(f!==\"4\"&&f!==\"6\")process.exit(13);"
            + "const family=Number(f),h=require(\"node:https\"),n=require(\"node:net\");"
            + "const t=setTimeout(()=>process.exit(11),5000);"
            + "const q=h.get({hostname:family===4?\"api.ipify.org\":\"api64.ipify.org\",family,port:443,path:\"/\",method:\"GET\",agent:false,maxHeaderSize:8192,headers:{Accept:\"text/plain\"}},r=>{"
            + "if(r.statusCode!==200){const a=r.headers?.[\"retry-after\"];let out=\"\";if(typeof a===\"string\"&&a.length<=128){const s=a.trim();"
            + "if(/^[0-9]{1,10}$/.test(s)&&Number(s)<=2147483647)out=\"retry-seconds:\"+Number(s);"
            + "else if(/^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), [0-9]{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$/.test(s)){const at=Date.parse(s);if(Number.isSafeInteger(at)&&at>0)out=\"retry-at:\"+at}}"
            + "if(out)process.stdout.write(out+\"\\n\",()=>process.exit(12));else process.exit(12);return}let size=0,b=[];"
            + "r.on(\"data\",x=>{size+=x.length;if(size>128)process.exit(13);b.push(x)});"
            + "r.on(\"error\",()=>process.exit(10));r.on(\"end\",()=>{const ip=Buffer.concat(b).toString(\"utf8\").trim();"
            + "if(n.isIP(ip)!==family)process.exit(13);process.stdout.write(ip+\"\\n\");clearTimeout(t)})});q.on(\"error\",()=>process.exit(10));";
    private static final Pattern STORED = Pattern.compile("\\{\"schema\":1,\"observedAtMs\":([1-9][0-9]{0,15}),\"status\":\"(ok|unavailable)\","
            + "\"publicIp\":(null|\"[0-9a-fA-F:.]{1,45}\"),\"publicObservedAtMs\":(null|[1-9][0-9]{0,15}),"
            + "\"privateIp\":(null|\"[0-9a-fA-F:.]{1,45}\"),\"privateObservedAtMs\":(null|[1-9][0-9]{0,15}),"
            + "\"source\":\"node-egress-ipify\"\\}");

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
        final String publicIp, privateIp;
        final Long publicObservedAtMs, privateObservedAtMs;
        Observation(long observedAtMs, String publicIp, Long publicObservedAtMs, String privateIp, Long privateObservedAtMs) {
            this(observedAtMs, publicIp, publicObservedAtMs, privateIp, privateObservedAtMs, 0);
        }
        Observation(long observedAtMs, String publicIp, Long publicObservedAtMs, String privateIp, Long privateObservedAtMs, long retryAtMs) {
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
            this.retryAtMs = retryAtMs;
        }
        String json() {
            return "{\"schema\":1,\"observedAtMs\":" + observedAtMs + ",\"status\":\"" + (publicIp == null ? "unavailable" : "ok")
                    + "\",\"publicIp\":" + quoted(publicIp) + ",\"publicObservedAtMs\":" + publicObservedAtMs
                    + ",\"privateIp\":" + quoted(privateIp) + ",\"privateObservedAtMs\":" + privateObservedAtMs + ",\"source\":\"" + SOURCE + "\"}";
        }
    }
    static Observation unavailable(long at) { return new Observation(at, null, null, null, null); }
    static Observation parse(String value) {
        if (value == null || value.length() > 1024) return null;
        Matcher match = STORED.matcher(value);
        if (!match.matches()) return null;
        try {
            Observation result = new Observation(Long.parseLong(match.group(1)), unquote(match.group(3)), time(match.group(4)), unquote(match.group(5)), time(match.group(6)));
            if ((result.publicIp != null) != match.group(2).equals("ok")) return null;
            return result;
        } catch (RuntimeException invalid) { return null; }
    }
    private static Long time(String value) { return value.equals("null") ? null : Long.parseLong(value); }
    private static String unquote(String value) { return value.equals("null") ? null : value.substring(1, value.length() - 1); }
    private static String quoted(String value) { return value == null ? "null" : "\"" + value + "\""; }

    static Observation read(NodeBinding binding, String sshAddress, boolean sudo, Runner runner, Clock clock) {
        String internal = null, external = null; Long privateAt = null, publicAt = null;
        long retryAtMs = 0;
        try {
            Response interfaces = runner.execute(PcMetadata.ADDRESSES_COMMAND, PcMetadata.MAX_RESPONSE_BYTES, 8000);
            if (interfaces.exit == 0) {
                internal = selectPrivate(interfaces.output, sshAddress);
                if (internal != null) privateAt = clock.now();
            }
        } catch (Exception unavailable) { /* Independent public/private failures remain unknown. */ }
        try {
            Response response = runner.execute(command(binding, sudo), MAX_OUTPUT_BYTES, 7000);
            // A second request is allowed only for an unavailable IP family/transport.
            // The native SSH runner rechecks ownership before dispatch; exceptions and
            // explicit HTTP refusals never initiate this fallback.
            if (response.exit == NETWORK_FAILURE || response.exit == REQUEST_TIMEOUT)
                response = runner.execute(command(binding, sudo, 6), MAX_OUTPUT_BYTES, 7000);
            if (response.exit == HTTP_FAILURE) retryAtMs = retryDeadline(response.output, clock.now());
            if (response.exit == 0 && response.output != null && response.output.getBytes(StandardCharsets.UTF_8).length <= MAX_OUTPUT_BYTES) {
                external = publicAddress(response.output.trim());
                if (external != null) publicAt = clock.now();
            }
        } catch (Exception unavailable) { /* No remote output or credentials enter diagnostics. */ }
        long at = clock.now();
        if (privateAt != null && privateAt > at) { internal = null; privateAt = null; }
        if (publicAt != null && publicAt > at) { external = null; publicAt = null; }
        return new Observation(at, external, publicAt, internal, privateAt, retryAtMs);
    }
    static String command(NodeBinding binding, boolean sudo) {
        return command(binding, sudo, 4);
    }
    static String command(NodeBinding binding, boolean sudo, int family) {
        if (binding == null) throw new IllegalArgumentException("Verified node binding required");
        if (family != 4 && family != 6) throw new IllegalArgumentException("Fixed IP family required");
        return DiscoveryPolicy.runtimeCommand(binding, sudo) + " exec " + binding.nodeContainerId
                + " /usr/bin/env -i /usr/local/bin/node -e " + GpuCollector.quote(SCRIPT) + " " + family;
    }
    private static long retryDeadline(String value, long now) {
        if (value == null || value.length() > MAX_OUTPUT_BYTES) return 0;
        String result = value.trim();
        try {
            if (result.matches("retry-seconds:[0-9]{1,10}")) {
                long seconds = Long.parseLong(result.substring(14));
                if (seconds > 2147483647L) return 0;
                return Math.min(MAX_SAFE_INTEGER, now + seconds * 1000L);
            }
            if (result.matches("retry-at:[1-9][0-9]{0,15}")) {
                long at = Long.parseLong(result.substring(9));
                return at > now && at <= MAX_SAFE_INTEGER ? at : 0;
            }
        } catch (NumberFormatException invalid) { }
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
