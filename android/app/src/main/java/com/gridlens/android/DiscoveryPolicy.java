package com.gridlens.android;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure, bounded discovery policy. No network, credential or host state lives here. */
public final class DiscoveryPolicy {
    public static final int PROBE_CONCURRENCY = 32;
    public static final int SSH_CONCURRENCY = 4;
    public static final int MAX_ADDRESSES = 262144;
    public static final int MAX_CONTAINERS = 512;
    public static final int MAX_OUTPUT = 256 * 1024;
    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private DiscoveryPolicy() { }

    public static final class Candidate {
        public final String address;
        public final int port;
        public final String name;
        public Candidate(String address, int port, String name) {
            this.address = numericAddress(address).getHostAddress();
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SSH port");
            this.port = port;
            this.name = safeName(name);
        }
        public String endpointKey() { return "[" + address + "]:" + port; }
    }

    public static String safeName(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").substring(0, Math.min(value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").length(), 120));
    }

    public static InetAddress numericAddress(String value) {
        if (value == null || value.length() > 96 || !value.matches("[0-9a-fA-F:.%]+(?:[a-zA-Z0-9]+)?") || !value.contains(".") && !value.contains(":"))
            throw new IllegalArgumentException("Use a numeric network address");
        if (!value.contains(":")) {
            String[] fields = value.split("\\.", -1);
            if (fields.length != 4) throw new IllegalArgumentException("Invalid IPv4 address");
            for (String field : fields) if (!field.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(field) > 255)
                throw new IllegalArgumentException("Invalid IPv4 address");
        }
        try {
            InetAddress result = InetAddress.getByName(value);
            if (result.isAnyLocalAddress() || result.isLoopbackAddress() || result.isMulticastAddress()) throw new IllegalArgumentException("Use a unicast network address");
            return result;
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Invalid network address"); }
    }

    /** IPv4 /16 through /32, or exactly one IPv6 host. Never guesses private ranges. */
    public static List<String> expandRanges(List<String> ranges) {
        if (ranges == null || ranges.isEmpty() || ranges.size() > 32) throw new IllegalArgumentException("Enter one or more network ranges");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String range : ranges) {
            String[] parts = range.trim().split("/", -1);
            if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) throw new IllegalArgumentException("Use CIDR network ranges");
            InetAddress address = numericAddress(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            if (address instanceof Inet6Address) {
                if (prefix != 128) throw new IllegalArgumentException("IPv6 requires an advertised address or /128 host route");
                result.add(address.getHostAddress());
                continue;
            }
            if (prefix < 16 || prefix > 32) throw new IllegalArgumentException("Choose an IPv4 range /16 or narrower");
            byte[] bytes = address.getAddress();
            long number = 0;
            for (byte b : bytes) number = (number << 8) | (b & 255);
            long count = 1L << (32 - prefix);
            long first = number & (0xffffffffL << (32 - prefix));
            long start = prefix < 31 ? 1 : 0;
            long end = prefix < 31 ? count - 1 : count;
            for (long n = start; n < end; n++) {
                long ip = first + n;
                String formatted = (ip >>> 24) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
                // A range crossing forbidden address classes is rejected as a whole.
                numericAddress(formatted);
                result.add(formatted);
                if (result.size() > MAX_ADDRESSES) throw new IllegalArgumentException("Network profile exceeds 262144 addresses; split it into profiles");
            }
        }
        return new ArrayList<>(result);
    }

    public static List<Integer> ports(List<Integer> extra) {
        LinkedHashSet<Integer> result = new LinkedHashSet<>();
        result.add(22);
        if (extra != null) for (Integer port : extra) {
            if (port == null || port < 1 || port > 65535) throw new IllegalArgumentException("Invalid SSH port");
            result.add(port);
        }
        if (result.size() > 8) throw new IllegalArgumentException("Choose at most eight SSH ports");
        return new ArrayList<>(result);
    }

    public static boolean publicAddress(String address) {
        if (address == null || address.length() < 32 || address.length() > 44) return false;
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < address.length(); i++) {
            int digit = BASE58.indexOf(address.charAt(i));
            if (digit < 0) return false;
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit));
        }
        int zeros = 0;
        while (zeros < address.length() && address.charAt(zeros) == '1') zeros++;
        return zeros + (value.signum() == 0 ? 0 : (value.bitLength() + 7) / 8) == 32;
    }

    public static final class Container {
        public final String id, image, name;
        Container(String id, String image, String name) { this.id = id; this.image = image; this.name = name; }
        public boolean wrapper() { return imageMatches(image, "podman"); }
        public boolean node() { return imageMatches(image, "nosana-node"); }
        public String proxyAddress() {
            if (!imageMatches(image, "frpc") || !name.startsWith("frpc-api-")) return null;
            String address = name.substring(9);
            return publicAddress(address) ? address : null;
        }
    }

    static boolean imageMatches(String image, String repository) {
        return image.matches("(?:(?:docker\\.io|registry\\.hub\\.docker\\.com)/)?nosana/" + repository + "(?::[A-Za-z0-9_.-]+|@sha256:[a-f0-9]{64})?");
    }

    /** Only explicit ID/image/name fields from running-container ps, never inspect/env/logs. */
    public static List<Container> parseInventory(String output) {
        if (output == null || output.length() > MAX_OUTPUT) throw new IllegalArgumentException("Inventory response too large");
        List<Container> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (String line : output.split("\\r?\\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 3 || !fields[0].matches("[a-f0-9]{64}") || !fields[1].matches("[A-Za-z0-9][A-Za-z0-9./:@_-]{0,255}") || !fields[2].matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || !ids.add(fields[0]))
                throw new IllegalArgumentException("Invalid container inventory");
            result.add(new Container(fields[0], fields[1], fields[2]));
            if (result.size() > MAX_CONTAINERS) throw new IllegalArgumentException("Container inventory exceeds limit");
        }
        return result;
    }

    // Nosana's already-running API root returns only its public address. The fixed reader
    // clears NODE_OPTIONS and loads only Node's built-in HTTP client, never Nosana initialization.
    static final String API_READER = "const h=require(\"node:http\");const t=setTimeout(()=>process.exit(1),3000);const q=h.get({host:\"127.0.0.1\",port:5001,path:\"/\"},r=>{let s=\"\";r.on(\"data\",b=>{s+=b;if(s.length>256)process.exit(1)});r.on(\"end\",()=>{try{const a=JSON.parse(s);if(r.statusCode!==200||typeof a!==\"string\"||! /^[1-9A-HJ-NP-Za-km-z]{32,44}$/.test(a))process.exit(1);process.stdout.write(a+\"\\n\");clearTimeout(t)}catch{process.exit(1)}})});q.on(\"error\",()=>process.exit(1));";
    public static String nodeApiCommand(String runtime, String wrapperId, String nodeId, boolean sudo) {
        if ((!runtime.equals("docker") && !runtime.equals("podman")) || nodeId == null || !nodeId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid node identity");
        String command = "/usr/bin/" + runtime;
        if (wrapperId != null) {
            if (!runtime.equals("docker") || !wrapperId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid wrapper identity");
            command += " exec " + wrapperId + " /usr/bin/podman";
        }
        return (sudo ? "/usr/bin/sudo -S -p '' -- " : "") + command + " exec " + nodeId + " /usr/bin/env -i /usr/local/bin/node -e '" + API_READER + "'";
    }
    static final String PS_ARGUMENTS = " ps --no-trunc --format '{{.ID}}\\t{{.Image}}\\t{{.Names}}'";
    public static String inventoryCommand(String runtime, String wrapperId, boolean sudo) {
        if (!runtime.equals("docker") && !runtime.equals("podman")) throw new IllegalArgumentException("Unsupported runtime");
        String command = "/usr/bin/" + runtime;
        if (wrapperId != null) {
            if (!runtime.equals("docker") || !wrapperId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid wrapper identity");
            command += " exec " + wrapperId + " /usr/bin/podman";
        }
        // No shell fragments or arbitrary paths from credentials, addresses or advertised names.
        return (sudo ? "/usr/bin/sudo -S -p '' -- " : "") + command + PS_ARGUMENTS;
    }
}
