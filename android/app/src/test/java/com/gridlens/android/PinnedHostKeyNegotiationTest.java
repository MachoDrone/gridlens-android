package com.gridlens.android;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.junit.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class PinnedHostKeyNegotiationTest {
    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair();
    }
    private static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec(curve)); return generator.generateKeyPair();
    }
    private static SshDiscovery.Identity identity(KeyPair pair) {
        String[] encoded = PublicKeyEntry.toString(pair.getPublic()).split(" ");
        return new SshDiscovery.Identity(encoded[1], encoded[0]);
    }
    private static final class Fixture implements AutoCloseable, SshDiscovery.Transport {
        final SshServer server = SshServer.setUpDefaultServer();
        final AtomicInteger logins = new AtomicInteger(), commands = new AtomicInteger();
        Fixture(KeyPair... offered) throws Exception {
            server.setHost("127.0.0.1"); server.setPort(0); server.setKeyPairProvider(KeyPairProvider.wrap(Arrays.asList(offered)));
            server.setPasswordAuthenticator((username, password, session) -> { logins.incrementAndGet(); return username.equals("fixture") && password.equals("password"); });
            server.setCommandFactory((channel, command) -> new Command() {
                OutputStream out; ExitCallback callback;
                public void setInputStream(InputStream value) { }
                public void setOutputStream(OutputStream value) { out = value; }
                public void setErrorStream(OutputStream value) { }
                public void setExitCallback(ExitCallback value) { callback = value; }
                public void start(ChannelSession channel, Environment environment) throws java.io.IOException {
                    commands.incrementAndGet();
                    String response = command.equals(PcMetadata.ADDRESSES_COMMAND) ? "192.168.10.101\n"
                            : command.equals(PublicIpReader.command(4)) ? "HTTP/1.1 200 OK\r\n\r\n8.8.8.8\n" : null;
                    if (response != null) { out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush(); }
                    callback.onExit(response == null ? 127 : 0);
                }
                public void destroy(ChannelSession channel) { }
            });
            server.start();
        }
        DiscoveryPolicy.Candidate candidate() { return new DiscoveryPolicy.Candidate("192.0.2.10", server.getPort(), ""); }
        public Socket connect(String address, int port, int timeout) throws java.io.IOException {
            Socket socket = new Socket(); socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), timeout); socket.setSoTimeout(timeout); return socket;
        }
        public void ensureActive() { }
        public boolean isCancelled() { return false; }
        SshDiscovery.NetworkResult read(SshDiscovery.Identity trusted) {
            try (SshDiscovery.Credential credential = new SshDiscovery.Credential("fixture", "password".getBytes(StandardCharsets.UTF_8), null, null)) {
                return SshDiscovery.readNetwork(this, candidate(), trusted, credential, () -> true);
            }
        }
        public void close() throws Exception { server.stop(true); }
    }
    @Test public void savedRsaPinRemainsUsableWhenServerAlsoOffersEcdsa() throws Exception {
        KeyPair known = rsa();
        try (Fixture fixture = new Fixture(known, ec("secp256r1"))) {
            SshDiscovery.NetworkResult result = fixture.read(identity(known));
            assertEquals("An additional host-key algorithm must not invalidate the exact key that is still offered", "active", result.status);
            assertEquals("8.8.8.8", result.observation.publicIp);
            assertEquals(1, fixture.logins.get()); assertEquals(2, fixture.commands.get());
        }
    }
    @Test public void savedEcdsaCurveIsPreferredOverAnotherOfferedCurve() throws Exception {
        KeyPair known = ec("secp384r1");
        try (Fixture fixture = new Fixture(rsa(), ec("secp256r1"), known)) {
            assertEquals("active", fixture.read(identity(known)).status);
            assertEquals(1, fixture.logins.get()); assertEquals(2, fixture.commands.get());
        }
    }
    @Test public void replacementOfThePinnedRsaKeyStillRejectsBeforeCredentials() throws Exception {
        KeyPair known = rsa();
        try (Fixture fixture = new Fixture(rsa(), ec("secp256r1"))) {
            assertEquals("identity-changed", fixture.read(identity(known)).status);
            assertEquals(0, fixture.logins.get()); assertEquals(0, fixture.commands.get());
        }
    }
    @Test public void removalOfThePinnedAlgorithmStillRejectsBeforeCredentials() throws Exception {
        KeyPair known = rsa();
        try (Fixture fixture = new Fixture(ec("secp256r1"))) {
            assertEquals("identity-changed", fixture.read(identity(known)).status);
            assertEquals(0, fixture.logins.get()); assertEquals(0, fixture.commands.get());
        }
    }
    @Test public void firstInspectionRetainsTheExistingAlgorithmPreferenceWithoutAuthentication() throws Exception {
        try (Fixture fixture = new Fixture(rsa(), ec("secp256r1"))) {
            assertEquals("ecdsa-sha2-nistp256", SshDiscovery.inspectIdentity(fixture, fixture.candidate()).algorithm);
            assertEquals(0, fixture.logins.get()); assertEquals(0, fixture.commands.get());
        }
    }
}
