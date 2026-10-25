package com.gridlens.android;

import org.junit.Test;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class RewardsReaderTest {
    private static final String SOURCE = "module.exports={}; // synthetic helper, no wallet\n";
    private static final String ADDRESS = "11111111111111111111111111111111";
    private static final String NODE = "a".repeat(64), WRAPPER = "b".repeat(64);
    private static String result(String status, String fields) {
        return "{\"schema\":1,\"address\":\"" + ADDRESS + "\",\"observedAtMs\":" + System.currentTimeMillis() + ",\"status\":\"" + status + "\"" + fields + "}";
    }
    private static final class Fixture implements RewardsReader.Runner {
        final RewardsReader.Binding binding;
        final Map<String, RewardsReader.Response> replies = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final boolean sudo;
        Fixture(String runtime, String wrapper, boolean sudo) {
            this.sudo = sudo; binding = new RewardsReader.Binding(runtime, wrapper, NODE, ADDRESS);
            if (wrapper != null) put(RewardsReader.inspectWrapper(binding, sudo), 0, wrapper + "\ttrue\tnosana/podman:latest\n");
            put(RewardsReader.inspectNode(binding, sudo), 0, NODE + "\ttrue\tnosana/nosana-node:1.2.3\n");
            put(RewardsReader.identityCommand(binding, sudo), 0, ADDRESS + "\n");
            put(RewardsReader.readCommand(SOURCE, binding, sudo), 0, result("ok", ",\"amount\":\"0.000000\""));
        }
        void put(String command, int exit, String output) { replies.put(command, new RewardsReader.Response(exit, output)); }
        public RewardsReader.Response execute(String command, int maxBytes, long timeoutMs) {
            calls.add(command);
            assertTrue(maxBytes <= 1024); assertTrue(timeoutMs <= 14000);
            assertNotNull("Unexpected fixed command", replies.get(command));
            return replies.get(command);
        }
        RewardsReader.Outcome collect(boolean authorized) { return RewardsReader.collect(SOURCE, binding, authorized, sudo, this); }
    }

    @Test public void noApprovalMeansNoCommandsOrCredentialHandling() {
        Fixture fixture = new Fixture("docker", null, false);
        assertEquals(RewardsReader.Status.NOT_AUTHORIZED, fixture.collect(false).status);
        assertTrue(fixture.calls.isEmpty());
        assertEquals(RewardsReader.Status.NOT_AUTHORIZED,
                RewardsReader.collect(null, fixture.binding, false, false, fixture).status);
    }
    @Test public void directReadChecksExactRunningNodeAndPublicIdentityFirst() {
        Fixture fixture = new Fixture("docker", null, false);
        RewardsReader.Outcome outcome = fixture.collect(true);
        assertEquals(RewardsReader.Status.OK, outcome.status);
        assertEquals("0.000000", outcome.amount);
        assertFalse(outcome.suspendAuthorization);
        assertEquals(List.of(RewardsReader.inspectNode(fixture.binding, false),
                RewardsReader.identityCommand(fixture.binding, false), RewardsReader.readCommand(SOURCE, fixture.binding, false)), fixture.calls);
    }
    @Test public void nestedPodmanRequiresExactRunningWrapperBeforeNode() {
        Fixture fixture = new Fixture("podman", WRAPPER, true);
        assertEquals(RewardsReader.Status.OK, fixture.collect(true).status);
        assertEquals(RewardsReader.inspectWrapper(fixture.binding, true), fixture.calls.get(0));
        assertEquals(4, fixture.calls.size());
        for (String command : fixture.calls) assertTrue(command.startsWith("/usr/bin/sudo -S -p '' -- /usr/bin/docker"));
        assertTrue(RewardsReader.readCommand(SOURCE, fixture.binding, true).contains(WRAPPER + " /usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin LANG=C /usr/bin/podman exec"));
    }
    @Test public void stoppedMissingUnrecognizedAndReplacedContainersNeverReachReader() {
        for (String inspected : List.of(NODE + "\tfalse\tnosana/nosana-node:latest", WRAPPER + "\ttrue\tnosana/nosana-node:latest",
                NODE + "\ttrue\tunrelated/nosana-node:latest", NODE + "\ttrue\tnosana/nosana-node:latest\textra")) {
            Fixture fixture = new Fixture("docker", null, false);
            fixture.put(RewardsReader.inspectNode(fixture.binding, false), 0, inspected);
            assertEquals(RewardsReader.Status.UNAVAILABLE, fixture.collect(true).status);
            assertEquals(1, fixture.calls.size());
        }
        Fixture nested = new Fixture("podman", WRAPPER, false);
        nested.put(RewardsReader.inspectWrapper(nested.binding, false), 0, WRAPPER + "\tfalse\tnosana/podman:latest");
        assertEquals(RewardsReader.Status.UNAVAILABLE, nested.collect(true).status);
        assertEquals(1, nested.calls.size());
    }
    @Test public void changedApiIdentitySuspendsBeforeAnyWalletRead() {
        Fixture fixture = new Fixture("docker", null, false);
        fixture.put(RewardsReader.identityCommand(fixture.binding, false), 0, "different-public-node");
        RewardsReader.Outcome outcome = fixture.collect(true);
        assertEquals(RewardsReader.Status.IDENTITY_MISMATCH, outcome.status);
        assertTrue(outcome.suspendAuthorization); assertNull(outcome.amount);
        assertEquals(2, fixture.calls.size());
    }
    @Test public void authenticatedFailuresRequireNewApprovalAndRateLimitsRetainLongDeadlines() {
        for (String status : List.of("authentication-failed", "identity-mismatch")) {
            RewardsReader.Outcome outcome = RewardsReader.parse(result(status, ""), ADDRESS, System.currentTimeMillis());
            assertTrue(outcome.suspendAuthorization); assertNull(outcome.amount);
        }
        RewardsReader.Outcome limited = RewardsReader.parse(result("rate-limited", ",\"retryAfterSeconds\":864000"), ADDRESS, System.currentTimeMillis());
        assertEquals(864000, limited.retryAfterSeconds); assertNull(limited.amount);
        assertFalse(limited.suspendAuthorization);
    }
    @Test public void wrongPcClockCannotClearAnAuthenticationSuspensionOrCooldown() {
        String future = "{\"schema\":1,\"address\":\"" + ADDRESS + "\",\"observedAtMs\":9007199254740991,\"status\":\"authentication-failed\",\"retryAfterSeconds\":864000}";
        RewardsReader.Outcome outcome = RewardsReader.parse(future, ADDRESS, System.currentTimeMillis());
        assertTrue(outcome.suspendAuthorization); assertEquals(864000, outcome.retryAfterSeconds);
        assertFalse(outcome.json.contains("9007199254740991"));
    }
    @Test public void malformedUnknownExtraAndUnboundedFieldsCannotLeaveNativeReader() {
        for (String invalid : List.of(result("ok", ""), result("unavailable", ",\"amount\":\"0.000000\""),
                result("ok", ",\"amount\":\"1.1234567\""), result("ok", ",\"amount\":\"-1.000000\""),
                result("ok", ",\"amount\":\"9223372036854.775808\""), result("rate-limited", ",\"retryAfterSeconds\":2147483648"),
                result("ok", ",\"amount\":\"1.000000\",\"authorization\":\"private-canary\""), "private-canary", "x".repeat(1025))) {
            RewardsReader.Outcome outcome = RewardsReader.parse(invalid, ADDRESS, System.currentTimeMillis());
            assertEquals(RewardsReader.Status.UNAVAILABLE, outcome.status); assertNull(outcome.amount);
            assertFalse(outcome.json.contains("private-canary"));
        }
    }
    @Test public void helperCommandClearsLoaderBeforeEnvAndSetsProcessLocalCoreGuards() {
        RewardsReader.Binding binding = new RewardsReader.Binding("docker", null, NODE, ADDRESS);
        String command = RewardsReader.readCommand(SOURCE, binding, false);
        assertTrue(command.startsWith("/usr/bin/docker exec --env 'LD_PRELOAD=' --env 'LD_AUDIT=' --env 'LD_LIBRARY_PATH='"));
        assertTrue(command.contains("/usr/bin/env -i PATH=/usr/local/bin:/usr/bin:/bin LANG=C /bin/sh -c"));
        assertTrue(command.contains("ulimit -S -c 0 && ulimit -H -c 0 && printf 0 > /proc/self/coredump_filter"));
        assertTrue(command.contains("--gridlens-approved-rewards"));
        assertFalse(command.contains("--privileged")); assertFalse(command.contains(" restart "));
        assertFalse(command.contains("nosana_key.json")); assertFalse(command.contains("docker.sock"));
        assertTrue(command.endsWith(RewardsReader.quote(SOURCE) + " " + ADDRESS));
    }
    @Test public void arbitraryBindingAndSourceArgumentsAreRejectedBeforeCommands() {
        for (String id : List.of("short", NODE + ";touch /tmp/no", "$(id)"))
            assertThrows(IllegalArgumentException.class, () -> new RewardsReader.Binding("docker", null, id, ADDRESS));
        assertThrows(IllegalArgumentException.class, () -> new RewardsReader.Binding("sh", null, NODE, ADDRESS));
        assertThrows(IllegalArgumentException.class, () -> new RewardsReader.Binding("docker", "bad", NODE, ADDRESS));
        assertThrows(IllegalArgumentException.class, () -> new RewardsReader.Binding("docker", null, NODE, "not-an-address"));
        RewardsReader.Binding binding = new RewardsReader.Binding("docker", null, NODE, ADDRESS);
        assertThrows(IllegalArgumentException.class, () -> RewardsReader.readCommand("x\0", binding, false));
        assertThrows(IllegalArgumentException.class, () -> RewardsReader.readCommand("x".repeat(65537), binding, false));
        assertEquals("'a'\\''b\n$HOME`id`'", RewardsReader.quote("a'b\n$HOME`id`"));
    }
}
