package com.gridlens.android;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** External emulator fixture. Loopback SSH, generated key, no command execution. */
public final class ProductionSshFixture {
    public static void main(String[] args) throws Exception {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1"); server.setPort(0);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        java.security.KeyPair key = generator.generateKeyPair();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        // Keep the advertised RSA pin while also offering an earlier default-choice
        // algorithm. Production must negotiate the saved key before alternatives.
        server.setKeyPairProvider(KeyPairProvider.wrap(java.util.List.of(key, ec.generateKeyPair())));
        server.setPasswordAuthenticator((user, password, session) -> user.equals("fixture") && password.equals("isolated-ssh-password"));
        Map<String,String> replies = new HashMap<>();
        replies.put(PcMetadata.NAME_COMMAND, "three-host-fixture\n");
        replies.put(PcMetadata.ADDRESSES_COMMAND, "192.168.10.101 172.17.0.1 172.18.0.1\n");
        replies.put(PublicIpReader.command(4), "HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n8.8.8.8\n");
        StringBuilder inventory = new StringBuilder();
        String[] names = {"render-worker-east", "arbitrary_blue_2", "compute.third"};
        for (int index = 1; index <= 3; index++) {
            NodeBinding binding = new NodeBinding("docker", null, "a".repeat(63) + index, "1".repeat(31) + index);
            inventory.append(binding.nodeContainerId).append("\tnosana/nosana-node:latest\t")
                    .append(names[index - 1]).append('\n');
            replies.put(DiscoveryPolicy.bindingInspectionCommand(binding, false), binding.nodeContainerId + "\ttrue\tnosana/nosana-node:latest\thost\n");
            replies.put(DiscoveryPolicy.bindingAddressCommand(binding, false), binding.address + "\n");
        }
        replies.put(DiscoveryPolicy.inventoryCommand("docker", null, false), inventory.toString());
        AtomicInteger publicReads = new AtomicInteger(), otherCommands = new AtomicInteger(), prohibitedCommands = new AtomicInteger(), unexpectedCommands = new AtomicInteger();
        server.setCommandFactory((channel, command) -> new Command() {
            OutputStream out; ExitCallback exit;
            public void setInputStream(InputStream value) { }
            public void setOutputStream(OutputStream value) { out = value; }
            public void setErrorStream(OutputStream value) { }
            public void setExitCallback(ExitCallback value) { exit = value; }
            public void start(ChannelSession channel, Environment environment) throws java.io.IOException {
                if (command.equals(PublicIpReader.command(4))) publicReads.incrementAndGet();
                else otherCommands.incrementAndGet();
                // Count unapproved authority without logging commands, generated credentials
                // or key material. Even an unexpected command is never executed.
                if (command.contains("sudo") || command.contains("/nosana_key.json") || command.contains("rewards")
                        || command.contains(" docker run ") || command.contains("/docker run ")
                        || command.contains(" volume create ") || command.contains(" pull ")
                        || command.contains(" container start ")) prohibitedCommands.incrementAndGet();
                String response = replies.get(command);
                if (response == null && !command.equals(DiscoveryPolicy.inventoryCommand("podman", null, false))
                        && !command.equals(GpuCollector.listContainer(false))) unexpectedCommands.incrementAndGet();
                if (response != null) { out.write(response.getBytes(StandardCharsets.UTF_8)); out.flush(); }
                exit.onExit(response == null ? 127 : 0);
            }
            public void destroy(ChannelSession channel) { }
        });
        server.start();
        String[] publicKey = PublicKeyEntry.toString(key.getPublic()).split(" ");
        System.out.println("{\"port\":" + server.getPort() + ",\"algorithm\":\"" + publicKey[0] + "\",\"keyBase64\":\"" + publicKey[1] + "\"}");
        System.out.flush();
        try { while (System.in.read() != -1) { } }
        finally {
            server.stop(true);
            System.out.println("{\"publicReads\":" + publicReads.get() + ",\"otherCommands\":" + otherCommands.get()
                    + ",\"prohibitedCommands\":" + prohibitedCommands.get() + ",\"unexpectedCommands\":" + unexpectedCommands.get() + "}");
        }
    }
    private ProductionSshFixture() { }
}
