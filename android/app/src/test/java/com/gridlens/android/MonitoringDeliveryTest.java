package com.gridlens.android;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public final class MonitoringDeliveryTest {
    private static final long AT = 1793102400000L;
    private static final String PC = "a".repeat(64);
    private static NodeBinding binding(int number) { return new NodeBinding("docker", null, String.format("%064x", number + 1), "1".repeat(31) + (char) ('1' + number)); }
    @Test public void sourceRejectionAndMalformedGpuNeverPreventOtherSourceDelivery() {
        NodeBinding node = binding(0);
        String network = new PublicIpReader.Observation(AT, "8.8.8.8", AT, "10.0.0.2", AT).json();
        for (String rejected : List.of("network", "rewards", "gpu")) {
            List<String> calls = new ArrayList<>();
            Set<String> accepted = MonitoringDelivery.deliver(PC, AT, new SshDiscovery.MonitoringResult(node, "{}", "{malformed", "active", false, 0, network),
                    () -> true, (source, metadata, payload) -> { calls.add(source); if (source.equals(rejected)) throw new java.io.IOException("Rejected source"); });
            assertEquals(List.of("network", "rewards", "gpu"), calls);
            assertEquals(2, accepted.size()); assertFalse(accepted.contains(rejected));
        }
    }
    @Test public void revokedGenerationStopsTheNextSourceAndRetainsAcceptedSource() {
        AtomicBoolean owned = new AtomicBoolean(true);
        List<String> calls = new ArrayList<>();
        Set<String> accepted = MonitoringDelivery.deliver(PC, AT,
                new SshDiscovery.MonitoringResult(binding(0), "{}", "{}", "active", false, 0, "{}"), owned::get,
                (source, metadata, payload) -> { calls.add(source); owned.set(false); });
        assertEquals(List.of("network"), calls); assertEquals(Set.of("network"), accepted);
        assertTrue(MonitoringDelivery.deliver(PC, AT, new SshDiscovery.MonitoringResult(binding(0), "{}", "{}", "active", false, 0, "{}"),
                owned::get, (source, metadata, payload) -> fail("Revoked delivery")).isEmpty());
    }
    @Test public void nativeObservationsMatchTheCrossLanguageStorageAndBrowserFixture() throws Exception {
        String[] names = {"nat-first-host", "nat-second-host", "shared-nat-second-pc", "public-only", "ipv6", "public-failed", "private-failed"};
        String[] publicIps = {"8.8.8.8", "8.8.8.8", "8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", null, "9.9.9.9"};
        String[] privateIps = {"192.168.1.20", "192.168.1.20", "192.168.2.20", null, "fd12::20", "10.0.0.20", null};
        List<String> cases = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            NodeBinding node = binding(i);
            String pcId = i < 2 ? PC : String.format("%064x", i + 1);
            List<String> commands = new ArrayList<>();
            PublicIpReader.Observation observation = PublicIpReader.read(node, "8.8.4.4", false, (command, maximum, timeout) -> {
                commands.add(command);
                if (command.equals(PcMetadata.ADDRESSES_COMMAND)) {
                    if (index == 6) throw new java.io.IOException("Private interface lookup failed");
                    return new PublicIpReader.Response(0, privateIps[index] == null ? "1.1.1.1" : privateIps[index]);
                }
                if (index == 4 && command.equals(PublicIpReader.command(node, false, 4))) return new PublicIpReader.Response(PublicIpReader.NETWORK_FAILURE, "");
                return new PublicIpReader.Response(publicIps[index] == null ? PublicIpReader.HTTP_FAILURE : 0, publicIps[index] == null ? "HTTP/1.1 503 Unavailable\r\n\r\n" : "HTTP/1.1 200 OK\r\n\r\n" + publicIps[index]);
            }, () -> AT);
            assertTrue("Every PC asks for external egress, including public-only and private lookup failures", commands.contains(PublicIpReader.command(node, false)));
            String reward = "{\"schema\":1,\"address\":\"" + node.address + "\",\"observedAtMs\":" + AT + ",\"status\":\"ok\",\"amount\":\"12.345678\"}";
            List<String> updates = new ArrayList<>(), rejected = new ArrayList<>();
            Set<String> accepted = MonitoringDelivery.deliver(pcId, AT,
                    new SshDiscovery.MonitoringResult(node, reward, "{\"malformed\":true}", "active", false, 0, observation.json()), () -> true,
                    (source, metadata, payload) -> { String json = metadata.substring(0, metadata.length() - 1) + ",\"" + source + "\":" + payload + "}"; if (source.equals("gpu")) { rejected.add(json); throw new java.io.IOException("GPU schema rejected"); } updates.add(json); });
            assertEquals(Set.of("network", "rewards"), accepted);
            cases.add("{\"name\":\"" + names[i] + "\",\"pcId\":\"" + pcId + "\",\"address\":\"" + node.address
                    + "\",\"expectedPublicIp\":" + quote(publicIps[i]) + ",\"expectedPrivateIp\":" + quote(privateIps[i])
                    + ",\"updates\":[" + String.join(",", updates) + "],\"rejectedUpdates\":[" + String.join(",", rejected) + "]}");
        }
        String generated = "{\"schema\":1,\"checkedAtMs\":" + AT + ",\"cases\":[\n" + String.join(",\n", cases) + "\n]}\n";
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("version.json"))) root = root.getParent();
        Path output = root.resolve("android/app/build/outputs/native-observations.json");
        Files.createDirectories(output.getParent()); Files.write(output, generated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("Native production serializers must match the cross-language fixture", new String(Files.readAllBytes(root.resolve("test/fixtures/native-observations.json")), java.nio.charset.StandardCharsets.UTF_8), generated);
    }
    private static String quote(String value) { return value == null ? "null" : "\"" + value + "\""; }
}
