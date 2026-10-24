package com.gridlens.android;

import com.jcraft.jsch.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Native SSH only. Commands, paths and secrets never arrive through Node or WebView. */
public final class SshDiscovery {
    private static final int CONNECT_MS = 8000;
    private SshDiscovery() { }
    public static void configureCryptoForAndroid() {
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH");
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA");
    }
    private static final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "gridlens-ssh-deadline"); thread.setDaemon(true); return thread;
    });
    private static ScheduledFuture<?> limit(Session session, long milliseconds) {
        return deadlines.schedule(session::disconnect, milliseconds, TimeUnit.MILLISECONDS);
    }
    public enum Status { SUCCESS, UNREACHABLE, AUTH_FAILED, IDENTITY_CHANGED, CONTAINER_ACCESS_REQUIRED, INVENTORY_FAILED, CANCELLED }
    public static final class Identity {
        public final String keyBase64, algorithm, fingerprint;
        public Identity(String keyBase64, String algorithm) {
            try {
                byte[] key = Base64.getDecoder().decode(keyBase64);
                if (key.length < 32 || key.length > 16384) throw new IllegalArgumentException();
                HostKey parsed = new HostKey("discovery", key);
                if (!parsed.getType().equals(algorithm) || !(algorithm.equals("ssh-ed25519") || algorithm.equals("ssh-rsa") || algorithm.startsWith("ecdsa-sha2-nistp"))) throw new IllegalArgumentException();
                this.keyBase64 = Base64.getEncoder().encodeToString(key);
                this.algorithm = algorithm;
                fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key));
            } catch (Exception e) { throw new IllegalArgumentException("Invalid SSH identity"); }
        }
    }
    public static final class Credential implements AutoCloseable {
        public final String username;
        final byte[] password, privateKey, passphrase;
        public Credential(String username, byte[] password, byte[] privateKey, byte[] passphrase) {
            if (username == null || !username.matches("[A-Za-z0-9_.@-]{1,64}")) throw new IllegalArgumentException("Invalid SSH username");
            if ((password == null || password.length == 0) == (privateKey == null || privateKey.length == 0)) throw new IllegalArgumentException("Choose one password or private key");
            if (password != null && password.length > 4096 || privateKey != null && privateKey.length > 65536 || passphrase != null && passphrase.length > 4096) throw new IllegalArgumentException("Credential exceeds size limit");
            this.username = username; this.password = copy(password); this.privateKey = copy(privateKey); this.passphrase = copy(passphrase);
        }
        @Override public void close() { wipe(password); wipe(privateKey); wipe(passphrase); }
    }
    public static void validateCredential(Credential credential) {
        if (credential.privateKey == null || credential.privateKey.length == 0) return;
        configureCryptoForAndroid(); JSch jsch = new JSch();
        try {
            jsch.addIdentity("native-import", credential.privateKey.clone(), null, copy(credential.passphrase));
            java.util.Vector<com.jcraft.jsch.Identity> identities = jsch.getIdentityRepository().getIdentities();
            if (identities.size() != 1 || identities.firstElement().isEncrypted()) throw new IllegalArgumentException("Private key or passphrase is unsupported or incorrect");
            String algorithm = identities.firstElement().getAlgName();
            if (!(algorithm.equals("ssh-rsa") || algorithm.equals("ssh-ed25519") || algorithm.startsWith("ecdsa-sha2-nistp"))) throw new IllegalArgumentException("Use an RSA, ECDSA or Ed25519 private key");
        } catch (JSchException error) { throw new IllegalArgumentException("Private key or passphrase is unsupported or incorrect"); }
        finally { try { jsch.removeAllIdentity(); } catch (JSchException ignored) { } }
    }
    public static final class Result {
        public final Status status;
        public final List<String> addresses;
        public final int unresolved;
        public Result(Status status, List<String> addresses, int unresolved) { this.status = status; this.addresses = Collections.unmodifiableList(new ArrayList<>(addresses)); this.unresolved = unresolved; }
        static Result of(Status status) { return new Result(status, Collections.emptyList(), 0); }
    }
    public static final class BatchRequest {
        public final DiscoveryPolicy.Candidate candidate;
        public final Identity identity;
        public final Credential credential;
        public final byte[] sudoPassword;
        public BatchRequest(DiscoveryPolicy.Candidate candidate, Identity identity, Credential credential, byte[] sudoPassword) {
            if (candidate == null || identity == null || credential == null) throw new IllegalArgumentException("Select and trust each computer first");
            this.candidate = candidate; this.identity = identity; this.credential = credential; this.sudoPassword = sudoPassword;
        }
    }
    public interface BatchProgress { void update(DiscoveryPolicy.Candidate candidate, Result result); }
    interface Transport {
        Socket connect(String address, int port, int timeout) throws IOException;
        void ensureActive() throws IOException;
        boolean isCancelled();
    }
    private static Transport transport(DiscoveryNetwork network) {
        return new Transport() {
            public Socket connect(String address, int port, int timeout) throws IOException { return network.connect(address, port, timeout); }
            public void ensureActive() throws IOException { network.ensureActive(); }
            public boolean isCancelled() { return network.isCancelled(); }
        };
    }
    public static Identity inspectIdentity(DiscoveryNetwork network, DiscoveryPolicy.Candidate candidate) throws IOException { return inspectIdentity(transport(network), candidate); }
    static Identity inspectIdentity(Transport network, DiscoveryPolicy.Candidate candidate) throws IOException {
        configureCryptoForAndroid();
        PinnedRepository repository = new PinnedRepository(null);
        Session session = null; ScheduledFuture<?> timeout = null;
        try {
            session = session(new JSch(), network, candidate, "gridlens-discovery", repository);
            session.setConfig("PreferredAuthentications", "none");
            timeout = limit(session, CONNECT_MS); session.connect(CONNECT_MS);
        } catch (JSchException ignored) { /* Strict rejection intentionally stops before user authentication. */ }
        finally { if (timeout != null) timeout.cancel(false); if (session != null) session.disconnect(); }
        network.ensureActive();
        if (repository.observed == null) throw new IOException("SSH identity could not be verified");
        return repository.observed;
    }
    private static Session session(JSch jsch, Transport network, DiscoveryPolicy.Candidate candidate, String username, PinnedRepository repository) throws JSchException, IOException {
        network.ensureActive();
        Session session = jsch.getSession(username, candidate.address, candidate.port);
        session.setHostKeyRepository(repository);
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("MaxAuthTries", "1");
        session.setConfig("server_host_key", "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256");
        // Android does not load multi-release Java 15 classes. BC uses its lightweight APIs.
        session.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519");
        session.setConfig("xdh", "com.jcraft.jsch.bc.XDH");
        session.setTimeout(CONNECT_MS);
        session.setSocketFactory(new com.jcraft.jsch.SocketFactory() {
            public Socket createSocket(String host, int port) throws IOException { return network.connect(host, port, CONNECT_MS); }
            public InputStream getInputStream(Socket socket) throws IOException { return socket.getInputStream(); }
            public OutputStream getOutputStream(Socket socket) throws IOException { return socket.getOutputStream(); }
        });
        return session;
    }
    static final class PinnedRepository implements HostKeyRepository {
        private final Identity expected;
        Identity observed; boolean changed;
        PinnedRepository(Identity expected) { this.expected = expected; }
        public int check(String host, byte[] key) {
            try { HostKey parsed = new HostKey(host, key); observed = new Identity(parsed.getKey(), parsed.getType()); }
            catch (Exception e) { changed = true; return CHANGED; }
            if (expected == null) return NOT_INCLUDED;
            changed = !MessageDigest.isEqual(Base64.getDecoder().decode(expected.keyBase64), key);
            return changed ? CHANGED : OK;
        }
        public void add(HostKey key, UserInfo ui) { throw new IllegalStateException("Native trust approval required"); }
        public void remove(String host, String type) { throw new IllegalStateException("Native trust approval required"); }
        public void remove(String host, String type, byte[] key) { throw new IllegalStateException("Native trust approval required"); }
        public String getKnownHostsRepositoryID() { return "native-session-pin"; }
        public HostKey[] getHostKey() { return new HostKey[0]; }
        public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }
    public static Result inventory(DiscoveryNetwork network, DiscoveryPolicy.Candidate candidate, Identity trusted, Credential credential, byte[] sudoPassword) { return inventory(transport(network), candidate, trusted, credential, sudoPassword); }
    static Result inventory(Transport network, DiscoveryPolicy.Candidate candidate, Identity trusted, Credential credential, byte[] sudoPassword) {
        if (trusted == null || credential == null) throw new IllegalArgumentException("Native trust and credentials required");
        if (sudoPassword != null && sudoPassword.length > 4096) throw new IllegalArgumentException("Sudo password exceeds size limit");
        configureCryptoForAndroid();
        JSch jsch = new JSch(); Session session = null; ScheduledFuture<?> timeout = null;
        PinnedRepository repository = new PinnedRepository(trusted);
        boolean connected = false;
        LinkedHashSet<String> addresses = new LinkedHashSet<>();
        int unresolved = 0;
        try {
            session = session(jsch, network, candidate, credential.username, repository);
            if (credential.privateKey != null && credential.privateKey.length > 0) {
                jsch.addIdentity("native-import", credential.privateKey.clone(), null, copy(credential.passphrase));
                session.setConfig("PreferredAuthentications", "publickey");
            } else {
                session.setPassword(credential.password.clone()); session.setConfig("PreferredAuthentications", "password");
            }
            timeout = limit(session, CONNECT_MS);
            session.connect(CONNECT_MS); connected = true;
            timeout.cancel(false); timeout = limit(session, 45000);
            long deadline = System.nanoTime() + 45000L * 1000000L;
            boolean any = false, denied = false;
            for (String runtime : new String[] {"docker", "podman"}) {
                CommandResult command = execute(session, network, DiscoveryPolicy.inventoryCommand(runtime, null, sudoPassword != null), sudoPassword, deadline);
                if (command.exit != 0) { if (command.exit != 127) denied = true; continue; }
                any = true;
                List<DiscoveryPolicy.Container> containers = DiscoveryPolicy.parseInventory(command.output);
                unresolved += collectWithApi(session, network, containers, addresses, runtime, null, sudoPassword, deadline);
                if (!runtime.equals("docker")) continue;
                int wrappers = 0;
                for (DiscoveryPolicy.Container wrapper : containers) if (wrapper.wrapper()) {
                    if (++wrappers > 32) throw new IOException("Wrapper inventory exceeds limit");
                    CommandResult nested = execute(session, network, DiscoveryPolicy.inventoryCommand("docker", wrapper.id, sudoPassword != null), sudoPassword, deadline);
                    if (nested.exit != 0) { denied = true; unresolved++; continue; }
                    unresolved += collectWithApi(session, network, DiscoveryPolicy.parseInventory(nested.output), addresses, "docker", wrapper.id, sudoPassword, deadline);
                }
            }
            network.ensureActive();
            return new Result(denied ? Status.CONTAINER_ACCESS_REQUIRED : any ? Status.SUCCESS : Status.INVENTORY_FAILED, new ArrayList<>(addresses), unresolved);
        } catch (JSchException e) {
            if (repository.changed) return Result.of(Status.IDENTITY_CHANGED);
            if (network.isCancelled()) return Result.of(Status.CANCELLED);
            if (e.getMessage() != null && (e.getMessage().startsWith("Auth fail") || e.getMessage().startsWith("Auth cancel") || e.getMessage().contains("invalid privatekey"))) return Result.of(Status.AUTH_FAILED);
            return new Result(connected ? Status.INVENTORY_FAILED : Status.UNREACHABLE, new ArrayList<>(addresses), unresolved);
        } catch (IOException | RuntimeException e) { return new Result(network.isCancelled() ? Status.CANCELLED : Status.INVENTORY_FAILED, new ArrayList<>(addresses), unresolved); }
        finally { if (timeout != null) timeout.cancel(false); if (session != null) session.disconnect(); try { jsch.removeAllIdentity(); } catch (JSchException ignored) { } }
    }
    private static int collectWithApi(Session session, Transport network, List<DiscoveryPolicy.Container> containers,
            LinkedHashSet<String> addresses, String runtime, String wrapper, byte[] sudoPassword, long deadline) throws IOException, JSchException {
        LinkedHashSet<String> local = new LinkedHashSet<>();
        int unresolved = collect(containers, local);
        addresses.addAll(local);
        if (unresolved > 0) {
            int nodes = 0;
            for (DiscoveryPolicy.Container container : containers) if (container.node()) {
                nodes++;
                CommandResult response = execute(session, network, DiscoveryPolicy.nodeApiCommand(runtime, wrapper, container.id, sudoPassword != null), sudoPassword, deadline);
                String address = response.output.trim();
                if (response.exit == 0 && DiscoveryPolicy.publicAddress(address)) { local.add(address); addresses.add(address); }
            }
            unresolved = Math.max(0, nodes - local.size());
        }
        addresses.addAll(local); return unresolved;
    }
    static int collect(List<DiscoveryPolicy.Container> containers, LinkedHashSet<String> addresses) {
        int nodes = 0; LinkedHashSet<String> proxies = new LinkedHashSet<>();
        for (DiscoveryPolicy.Container container : containers) {
            if (container.node()) nodes++;
            String address = container.proxyAddress(); if (address != null) proxies.add(address);
        }
        // PC-level public discovery makes no physical GPU or container-to-address pairing claim.
        if (nodes > 0 && proxies.size() <= nodes) { addresses.addAll(proxies); return nodes - proxies.size(); }
        return nodes;
    }
    private static final class CommandResult {
        final int exit; final String output;
        CommandResult(int exit, String output) { this.exit = exit; this.output = output; }
    }
    private static CommandResult execute(Session session, Transport network, String command, byte[] sudoPassword, long inventoryDeadline) throws IOException, JSchException {
        network.ensureActive();
        long deadline = Math.min(inventoryDeadline, System.nanoTime() + 8000L * 1000000L);
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setPty(false); channel.setAgentForwarding(false); channel.setCommand(command);
        channel.setErrStream(new OutputStream() { @Override public void write(int b) { } @Override public void write(byte[] b, int o, int n) { } });
        InputStream input = channel.getInputStream(); OutputStream stdin = channel.getOutputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            channel.connect(CONNECT_MS);
            if (sudoPassword != null) { stdin.write(sudoPassword); stdin.write('\n'); stdin.flush(); }
            stdin.close(); byte[] buffer = new byte[4096];
            for (;;) {
                network.ensureActive();
                if (System.nanoTime() >= deadline) throw new IOException("Inventory timed out");
                while (input.available() > 0) {
                    int read = input.read(buffer, 0, Math.min(buffer.length, input.available()));
                    if (read < 0) break;
                    if (output.size() + read > DiscoveryPolicy.MAX_OUTPUT) throw new IOException("Inventory response too large");
                    output.write(buffer, 0, read);
                }
                if (channel.isClosed() && input.available() == 0) return new CommandResult(channel.getExitStatus(), output.toString(StandardCharsets.UTF_8.name()));
                try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Inventory cancelled"); }
            }
        } finally { channel.disconnect(); }
    }
    public static List<Result> runBatch(DiscoveryNetwork network, List<BatchRequest> requests, BatchProgress progress) {
        return runBatch(requests, progress, request -> inventory(network, request.candidate, request.identity, request.credential, request.sudoPassword), network::isCancelled);
    }
    interface Inventory { Result read(BatchRequest request); }
    static List<Result> runBatch(List<BatchRequest> requests, BatchProgress progress, Inventory inventory, java.util.function.BooleanSupplier cancelled) {
        if (requests.size() > 512) throw new IllegalArgumentException("Select at most 512 computers per batch");
        Result[] results = new Result[requests.size()];
        AtomicInteger next = new AtomicInteger(); AtomicBoolean stopped = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(DiscoveryPolicy.SSH_CONCURRENCY); List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < DiscoveryPolicy.SSH_CONCURRENCY; worker++) tasks.add(executor.submit(() -> {
                while (!stopped.get() && !cancelled.getAsBoolean()) {
                    int index = next.getAndIncrement(); if (index >= requests.size() || stopped.get()) return;
                    BatchRequest request = requests.get(index); Result result = inventory.read(request); results[index] = result;
                    if (result.status == Status.AUTH_FAILED) stopped.set(true);
                    if (progress != null) progress.update(request.candidate, result);
                }
            }));
            for (Future<?> task : tasks) task.get();
        } catch (Exception e) { stopped.set(true); if (e instanceof InterruptedException) Thread.currentThread().interrupt(); }
        finally { executor.shutdownNow(); }
        for (int i = 0; i < results.length; i++) if (results[i] == null) {
            results[i] = Result.of(Status.CANCELLED); if (progress != null) progress.update(requests.get(i).candidate, results[i]);
        }
        return Arrays.asList(results);
    }
    static byte[] copy(byte[] bytes) { return bytes == null ? null : bytes.clone(); }
    static void wipe(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
}
