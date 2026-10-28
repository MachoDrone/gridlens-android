package com.gridlens.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public final class PublicIpReaderTest {
    private static final NodeBinding BINDING = new NodeBinding("docker", null, "a".repeat(64), "1".repeat(32));
    @Test public void publicAndPrivateReadingsHaveTheirOwnTimesAndOnlyPcOriginCommands() {
        AtomicLong clock = new AtomicLong(1000);
        List<String> commands = new ArrayList<>();
        PublicIpReader.Observation reading = PublicIpReader.read(BINDING, "192.168.1.20", false, (command, maxBytes, timeout) -> {
            commands.add(command);
            if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "172.17.0.1 192.168.1.20 fd12::5");
            assertEquals(PublicIpReader.command(BINDING, false), command);
            assertTrue(command.contains("curl -q -4")); assertFalse(command.contains("docker")); assertFalse(command.contains("sudo"));
            assertEquals(16384, maxBytes); assertEquals(11000, timeout);
            return new PublicIpReader.Response(0, "HTTP/1.1 200 OK\r\n\r\n8.8.8.8\n");
        }, clock::incrementAndGet);
        assertEquals(2, commands.size()); assertEquals("192.168.1.20", reading.privateIp); assertEquals("8.8.8.8", reading.publicIp);
        assertEquals(Long.valueOf(1001), reading.privateObservedAtMs); assertEquals(Long.valueOf(1002), reading.publicObservedAtMs);
        assertEquals(1003, reading.observedAtMs); assertEquals(reading.json(), PublicIpReader.parse(reading.json()).json());
        assertTrue(reading.json().contains("\"source\":\"pc-egress-ipify\""));
    }
    @Test public void failedPublicLookupKeepsActualPrivateObservationWithoutInventingPublicIp() {
        PublicIpReader.Observation reading = PublicIpReader.read(BINDING, "10.9.9.9", false, (command, maxBytes, timeout) ->
                command.equals(PcMetadata.ADDRESSES_COMMAND) ? new PublicIpReader.Response(0, "192.168.1.20") : new PublicIpReader.Response(1, "untrusted diagnostic"), () -> 2000);
        assertNull(reading.publicIp); assertNull(reading.publicObservedAtMs);
        assertEquals("192.168.1.20", reading.privateIp); assertEquals(Long.valueOf(2000), reading.privateObservedAtMs);
        assertTrue(reading.json().contains("\"status\":\"unavailable\"")); assertFalse(reading.json().contains("diagnostic"));
    }
    @Test public void privateSelectionRequiresReportedInterfaceRatherThanAssumingSshDestination() {
        assertEquals("192.168.1.20", PublicIpReader.selectPrivate("172.17.0.1 192.168.1.20", "192.168.1.20"));
        assertEquals("172.17.0.1", PublicIpReader.selectPrivate("172.17.0.1 192.168.1.20", "10.8.0.7"));
        assertEquals("100.64.1.20", PublicIpReader.selectPrivate("100.64.1.20 fd12::5", "100.64.1.20"));
        assertNull(PublicIpReader.selectPrivate("8.8.8.8 127.0.0.1 169.254.10.1", "10.8.0.7"));
        assertNotNull(PublicIpReader.selectPrivate("fd12::5", "fd12::5"));
    }
    @Test public void acceptsGlobalIpv4Ipv6AndRejectsPrivateReservedOrHostnames() {
        assertEquals("8.8.8.8", PublicIpReader.publicAddress("8.8.8.8"));
        assertNotNull(PublicIpReader.publicAddress("2606:4700::1111"));
        for (String address : List.of("10.0.0.1", "172.16.0.1", "192.168.1.2", "100.64.1.2", "169.254.2.3", "127.0.0.1", "0.1.2.3", "240.0.0.1",
                "192.0.2.1", "198.51.100.2", "203.0.113.3", "198.18.2.3", "fd00::1", "fe80::1", "::1", "::", "2001:db8::1", "2001:2::1", "2001:10::1", "2001:20::1", "example.test", "8.8.8.8; id"))
            assertNull(address, PublicIpReader.publicAddress(address));
    }
    @Test public void invalidOrOversizeRemoteBodyCannotBecomePublicAddress() {
        for (String body : List.of("{\"ip\":\"8.8.8.8\"}", "10.1.2.3", "8.8.8.8\n9.9.9.9", "x".repeat(129))) {
            PublicIpReader.Observation reading = PublicIpReader.read(BINDING, "10.0.0.1", false, (command, maxBytes, timeout) -> new PublicIpReader.Response(0, body), () -> 1000);
            assertNull(reading.publicIp); assertTrue(reading.json().contains("\"status\":\"unavailable\""));
        }
    }
    @Test public void storedCacheCannotRenewOrMixInvalidTimestampsAndAddresses() {
        String json = new PublicIpReader.Observation(1000, "8.8.8.8", 900L, "10.0.0.1", 800L).json();
        assertEquals(json, PublicIpReader.parse(json).json());
        assertNull(PublicIpReader.parse(json.replace("\"ok\"", "\"unavailable\"")));
        assertNull(PublicIpReader.parse(json.replace("\"publicObservedAtMs\":900", "\"publicObservedAtMs\":1001")));
        assertNull(PublicIpReader.parse(json.replace("8.8.8.8", "127.0.0.1")));
        assertNull(PublicIpReader.parse(json.replace("pc-egress-ipify", "phone-ip")));
    }
    @Test public void unavailableIpv4UsesOneNativeControlledIpv6Fallback() {
        List<String> commands = new ArrayList<>();
        PublicIpReader.Observation result = PublicIpReader.read(BINDING, "10.0.0.1", false, (command, maxBytes, timeout) -> {
            commands.add(command);
            if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "10.0.0.1");
            if (command.equals(PublicIpReader.command(BINDING, false, 4))) return new PublicIpReader.Response(PublicIpReader.NETWORK_FAILURE, "");
            assertEquals(PublicIpReader.command(BINDING, false, 6), command);
            return new PublicIpReader.Response(0, "HTTP/2 200\r\n\r\n2606:4700::1111\n");
        }, () -> 1000);
        assertEquals(3, commands.size()); assertNotNull(result.publicIp);
        assertTrue(result.publicIp.contains(":")); assertEquals("10.0.0.1", result.privateIp);
    }
    @Test public void cancellationAndHttpRefusalNeverInitiateFamilyFallback() {
        for (boolean cancelled : new boolean[]{false, true}) {
            List<String> commands = new ArrayList<>();
            PublicIpReader.Observation result = PublicIpReader.read(BINDING, "10.0.0.1", false, (command, maxBytes, timeout) -> {
                commands.add(command);
                if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "10.0.0.1");
                if (cancelled) throw new java.io.IOException("Captured network cancelled");
                return new PublicIpReader.Response(PublicIpReader.HTTP_FAILURE, "HTTP/1.1 429 Too Many Requests\r\nRetry-After: 3600\r\n\r\n");
            }, () -> 1000);
            assertEquals(2, commands.size()); assertNull(result.publicIp); assertEquals("10.0.0.1", result.privateIp);
            assertEquals(cancelled ? 0 : 3_601_000, result.retryAtMs);
            assertEquals(!cancelled, result.json().contains("retryAtMs"));
        }
    }
    @Test public void legacyCachePreservesItsSourceAndDatesWithoutMigration() {
        String legacy = new PublicIpReader.Observation(1000, "8.8.8.8", 900L, "10.0.0.1", 800L).json().replace("pc-egress-ipify", "node-egress-ipify");
        assertEquals(legacy, PublicIpReader.parse(legacy).json());
    }
    @Test public void boundedFailuresAndHttpEvidencePreventIncorrectFallbacks() {
        int[] codes = {127, 6, 7, 28, 60, 63};
        String[] failures = {"missing-curl", "dns", "connection", "timeout", "tls", "invalid-response"};
        for (int i = 0; i < codes.length; i++) {
            final int code = codes[i]; AtomicLong requests = new AtomicLong();
            PublicIpReader.Observation result = PublicIpReader.read("10.0.0.1", (command, max, timeout) -> {
                if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(127, "secret diagnostic");
                requests.incrementAndGet(); return new PublicIpReader.Response(code, "secret diagnostic");
            }, () -> 1000);
            assertEquals(failures[i], result.publicFailure); assertEquals("interface-read-failure", result.privateFailure);
            assertEquals(code == 6 || code == 7 || code == 28 ? 2 : 1, requests.get());
            assertFalse(result.json().contains("secret")); assertEquals(result.json(), PublicIpReader.parse(result.json()).json());
        }
        for (int exit : new int[]{0, 28, 63}) for (int status : new int[]{301, 403, 429, 503}) {
            AtomicLong requests = new AtomicLong();
            PublicIpReader.Observation result = PublicIpReader.read("10.0.0.1", (command, max, timeout) -> {
                if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "10.0.0.1");
                requests.incrementAndGet(); return new PublicIpReader.Response(exit, "HTTP/1.1 " + status + " Refused\r\nRetry-After: 7200\r\n\r\n");
            }, () -> 1000);
            assertEquals(1, requests.get()); assertEquals("http-refusal", result.publicFailure);
            assertEquals(Integer.valueOf(status), result.httpStatus); assertEquals(7201000, result.retryAtMs);
        }
    }
    @Test public void capturedNetworkRevocationAfterIpv4FailurePreventsFallbackDispatch() {
        java.util.concurrent.atomic.AtomicBoolean owned = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger externalRequests = new java.util.concurrent.atomic.AtomicInteger();
        PublicIpReader.Observation result = PublicIpReader.read(BINDING, "10.0.0.1", false, (command, maxBytes, timeout) -> {
            if (!owned.get()) throw new java.io.IOException("Captured network no longer owned");
            if (command.equals(PcMetadata.ADDRESSES_COMMAND)) return new PublicIpReader.Response(0, "10.0.0.1");
            externalRequests.incrementAndGet(); owned.set(false);
            return new PublicIpReader.Response(PublicIpReader.NETWORK_FAILURE, "");
        }, () -> 1000);
        assertEquals(1, externalRequests.get()); assertNull(result.publicIp); assertEquals("10.0.0.1", result.privateIp);
    }
    @Test public void aSuccessfulResponseMustMatchTheRequestedAddressFamilyAndRetainProviderWait() {
        PublicIpReader.Observation wrong = PublicIpReader.read("10.0.0.1", (command, max, timeout) ->
                command.equals(PcMetadata.ADDRESSES_COMMAND) ? new PublicIpReader.Response(0, "10.0.0.1")
                : new PublicIpReader.Response(0, "HTTP/1.1 200 OK\r\n\r\n2606:4700::1111"), () -> 1000);
        assertNull(wrong.publicIp); assertEquals("invalid-response", wrong.publicFailure);
        PublicIpReader.Observation wait = PublicIpReader.read("10.0.0.1", (command, max, timeout) ->
                command.equals(PcMetadata.ADDRESSES_COMMAND) ? new PublicIpReader.Response(0, "10.0.0.1")
                : new PublicIpReader.Response(0, "HTTP/1.1 200 OK\r\nRetry-After: 7200\r\n\r\n8.8.8.8"), () -> 1000);
        assertEquals("8.8.8.8", wait.publicIp); assertEquals(7201000, wait.retryAtMs);
    }

}
