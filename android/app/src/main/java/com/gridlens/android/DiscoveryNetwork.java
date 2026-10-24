package com.gridlens.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** One explicit discovery session, irrevocably attached to one Android network. */
public final class DiscoveryNetwork implements AutoCloseable {
    private static final java.util.concurrent.atomic.AtomicLong searches = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong connections = new java.util.concurrent.atomic.AtomicLong();
    static long startedSearchesForTesting() { return searches.get(); }
    static long startedConnectionsForTesting() { return connections.get(); }
    public interface Progress { void update(int completed, int total, int found); }
    public final List<String> suggestedRanges;
    public final boolean vpn;
    private final ConnectivityManager manager;
    private final Network network;
    private final LinkProperties links;
    private final Set<Socket> sockets = Collections.synchronizedSet(new LinkedHashSet<>());
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConnectivityManager.NetworkCallback callback;
    private final ConnectivityManager.NetworkCallback physicalCallback;
    private final Map<Network, LinkProperties> physicalLinks = new LinkedHashMap<>();
    private final AtomicBoolean overflow = new AtomicBoolean();
    private final NsdManager nsd;
    private final String transportIdentity;
    private final java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo> pendingServices = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final AtomicBoolean resolving = new AtomicBoolean();
    private final Map<String, DiscoveryPolicy.Candidate> advertised = Collections.synchronizedMap(new LinkedHashMap<>());
    private NsdManager.DiscoveryListener serviceListener;

    public static DiscoveryNetwork capture(Context context) throws IOException {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network active = manager.getActiveNetwork();
        if (active == null) throw new IOException("Connect to Wi-Fi or a VPN before searching");
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        LinkProperties links = manager.getLinkProperties(active);
        if (capabilities == null || links == null) throw new IOException("Network information unavailable");
        boolean vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        if (!vpn && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) throw new IOException("Connect to Wi-Fi or a VPN before searching");
        return new DiscoveryNetwork(context, manager, active, links, vpn);
    }
    private DiscoveryNetwork(Context context, ConnectivityManager manager, Network network, LinkProperties links, boolean vpn) throws IOException {
        this.manager = manager; this.network = network; this.links = links; this.vpn = vpn;
        this.nsd = context.getSystemService(NsdManager.class);
        this.transportIdentity = transports(manager.getNetworkCapabilities(network));
        if (vpn) for (Network physical : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(physical);
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) physicalLinks.put(physical, manager.getLinkProperties(physical));
        }
        List<DiscoveryRoutePolicy.Prefix> routeFacts = new ArrayList<>(), linkFacts = new ArrayList<>();
        for (LinkAddress local : links.getLinkAddresses()) linkFacts.add(new DiscoveryRoutePolicy.Prefix(
                local.getAddress().getHostAddress(), local.getPrefixLength(), true));
        for (RouteInfo route : links.getRoutes()) routeFacts.add(new DiscoveryRoutePolicy.Prefix(
                route.getDestination().getAddress().getHostAddress(), route.getDestination().getPrefixLength(),
                Build.VERSION.SDK_INT < 33 || route.getType() == RouteInfo.RTN_UNICAST));
        this.suggestedRanges = DiscoveryRoutePolicy.infer(vpn, linkFacts, routeFacts);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network value) { if (!network.equals(value)) cancel(); }
            @Override public void onLost(Network value) { if (network.equals(value)) cancel(); }
            @Override public void onLinkPropertiesChanged(Network value, LinkProperties current) {
                if (network.equals(value) && !DiscoveryNetwork.this.links.equals(current)) cancel();
            }
            @Override public void onCapabilitiesChanged(Network value, NetworkCapabilities current) {
                if (network.equals(value) && !transportIdentity.equals(transports(current))) cancel();
            }
        };
        physicalCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network value) { if (!samePhysical(value, manager.getLinkProperties(value), true)) cancel(); }
            @Override public void onLost(Network value) { if (!samePhysical(value, null, false)) cancel(); }
            @Override public void onLinkPropertiesChanged(Network value, LinkProperties current) {
                if (!samePhysical(value, current, true)) cancel();
            }
        };
        manager.registerDefaultNetworkCallback(callback);
        boolean physicalRegistered = false;
        try {
            if (vpn) {
                manager.registerNetworkCallback(new android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), physicalCallback);
                physicalRegistered = true;
            }
            ensureActive();
        } catch (IOException | RuntimeException e) {
            manager.unregisterNetworkCallback(callback);
            if (physicalRegistered) manager.unregisterNetworkCallback(physicalCallback);
            throw e;
        }
    }
    private boolean samePhysical(Network changed, LinkProperties properties, boolean present) {
        Map<String, String> before = new LinkedHashMap<>();
        for (Map.Entry<Network, LinkProperties> entry : physicalLinks.entrySet())
            before.put(entry.getKey().toString(), String.valueOf(entry.getValue()));
        Map<String, String> current = new LinkedHashMap<>(before);
        if (present) current.put(changed.toString(), String.valueOf(properties)); else current.remove(changed.toString());
        return DiscoveryRoutePolicy.samePhysicalNetworks(before, current);
    }
    private static String transports(NetworkCapabilities caps) {
        if (caps == null) return "none";
        StringBuilder result = new StringBuilder();
        for (int type = 0; type <= 10; type++) if (caps.hasTransport(type)) result.append(type).append(',');
        return result.toString();
    }
    public boolean isCancelled() { return cancelled.get(); }
    public void ensureActive() throws IOException {
        Network active = manager.getActiveNetwork();
        LinkProperties currentLinks = manager.getLinkProperties(network);
        if (cancelled.get() || !DiscoveryRoutePolicy.sameSession(network.toString(), active == null ? null : active.toString(),
                links.toString(), currentLinks == null ? null : currentLinks.toString(), transportIdentity,
                transports(manager.getNetworkCapabilities(network)))) {
            cancel(); throw new IOException("Discovery cancelled because the active network changed");
        }
    }
    public void cancel() {
        cancelled.set(true);
        synchronized (sockets) { for (Socket socket : sockets) { try { socket.close(); } catch (IOException ignored) { } } sockets.clear(); }
    }
    public Socket connect(String address, int port, int timeout) throws IOException {
        ensureActive();
        connections.incrementAndGet();
        Socket socket = network.getSocketFactory().createSocket();
        sockets.add(socket);
        try {
            ensureActive();
            socket.connect(new InetSocketAddress(DiscoveryPolicy.numericAddress(address), port), timeout);
            socket.setSoTimeout(timeout);
            ensureActive();
            return socket;
        } catch (IOException | RuntimeException error) { release(socket); throw error; }
    }
    void release(Socket socket) { sockets.remove(socket); try { socket.close(); } catch (IOException ignored) { } }
    /** Optional advertisements use the selected network, never SSH identity evidence. */
    private void startAdvertisements() {
        if (Build.VERSION.SDK_INT < 33 || android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 3 || nsd == null || serviceListener != null) return;
        serviceListener = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) { }
            public void onDiscoveryStopped(String type) { }
            public void onStartDiscoveryFailed(String type, int code) { }
            public void onStopDiscoveryFailed(String type, int code) { }
            public void onServiceLost(NsdServiceInfo info) { }
            public void onServiceFound(NsdServiceInfo info) {
                if (Build.VERSION.SDK_INT < 33 || android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 3 || cancelled.get() || info.getNetwork() == null || !network.equals(info.getNetwork())) return;
                if (pendingServices.size() < 512) { pendingServices.add(info); resolveNext(); } else { overflow.set(true); cancel(); }
            }
        };
        try { nsd.discoverServices("_ssh._tcp.", NsdManager.PROTOCOL_DNS_SD, network, Runnable::run, serviceListener); }
        catch (RuntimeException ignored) { serviceListener = null; }
    }
    private void resolveNext() {
        if (cancelled.get() || !resolving.compareAndSet(false, true)) return;
        NsdServiceInfo info = pendingServices.poll();
        if (info == null) { resolving.set(false); return; }
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                private void next() { resolving.set(false); resolveNext(); }
                public void onResolveFailed(NsdServiceInfo value, int code) { next(); }
                public void onServiceResolved(NsdServiceInfo value) {
                    try {
                        if (Build.VERSION.SDK_INT >= 33 && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 3 && !cancelled.get() && network.equals(value.getNetwork()) && value.getHost() != null) {
                            DiscoveryPolicy.Candidate candidate = new DiscoveryPolicy.Candidate(value.getHost().getHostAddress(), value.getPort(), value.getServiceName());
                            if (advertised.size() < 512) advertised.put(candidate.endpointKey(), candidate); else { overflow.set(true); cancel(); }
                        }
                    } catch (IllegalArgumentException ignored) { }
                    finally { next(); }
                }
            });
        } catch (RuntimeException ignored) { resolving.set(false); pendingServices.clear(); }
    }
    public List<DiscoveryPolicy.Candidate> scan(List<String> ranges, List<Integer> ports, Progress progress) throws IOException {
        ensureActive();
        searches.incrementAndGet();
        List<String> addresses = ranges.isEmpty() ? Collections.emptyList() : DiscoveryPolicy.expandRanges(ranges);
        List<Integer> checkedPorts = DiscoveryPolicy.ports(ports);
        startAdvertisements();
        long advertisementDeadline = System.nanoTime() + 2000000000L;
        try {
            Map<String, DiscoveryPolicy.Candidate> found = new LinkedHashMap<>();
            for (DiscoveryPolicy.Candidate candidate : DiscoveryScanner.scan(addresses, checkedPorts, this::probe, this::isCancelled,
                    progress == null ? null : progress::update)) found.put(candidate.endpointKey(), candidate);
            if (overflow.get()) throw new IOException("Too many advertised services; narrow the network profile");
            ensureActive();
            while (serviceListener != null && System.nanoTime() < advertisementDeadline) {
                ensureActive(); Thread.sleep(25);
            }
            List<DiscoveryPolicy.Candidate> services;
            synchronized (advertised) { services = new ArrayList<>(advertised.values()); }
            List<DiscoveryPolicy.Candidate> pending = new ArrayList<>();
            for (DiscoveryPolicy.Candidate candidate : services) {
                if (found.containsKey(candidate.endpointKey())) found.put(candidate.endpointKey(), candidate);
                else pending.add(candidate);
            }
            for (DiscoveryPolicy.Candidate candidate : DiscoveryScanner.scanCandidates(pending, this::probe, this::isCancelled,
                    progress == null ? null : progress::update)) {
                if (found.size() >= 2048) throw new IOException("Too many SSH candidates; narrow the network profile");
                found.put(candidate.endpointKey(), candidate);
            }
            ensureActive();
            List<DiscoveryPolicy.Candidate> result = new ArrayList<>(found.values());
            result.sort(Comparator.comparing(DiscoveryPolicy.Candidate::endpointKey));
            return result;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel(); throw new IOException("Discovery cancelled"); }
        finally { stopAdvertisements(); }
    }
    private boolean probe(DiscoveryPolicy.Candidate candidate) {
        Socket socket = null;
        try {
            socket = connect(candidate.address, candidate.port, 750);
            long deadline = System.nanoTime() + 750000000L;
            InputStream input = socket.getInputStream();
            StringBuilder line = new StringBuilder();
            int bytes = 0;
            while (bytes++ < 1024) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                socket.setSoTimeout((int) Math.max(1, remaining / 1000000));
                int next = input.read();
                if (next < 0) return false;
                if (next == '\n') {
                    if (line.toString().startsWith("SSH-2.0-") || line.toString().startsWith("SSH-1.99-")) return true;
                    line.setLength(0);
                } else if (next != '\r') line.append((char) next);
            }
        } catch (IOException | RuntimeException ignored) { }
        finally { if (socket != null) release(socket); }
        return false;
    }
    private void stopAdvertisements() {
        pendingServices.clear();
        if (serviceListener != null && nsd != null) {
            try { nsd.stopServiceDiscovery(serviceListener); } catch (RuntimeException ignored) { }
            serviceListener = null;
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancel(); stopAdvertisements(); manager.unregisterNetworkCallback(callback);
        if (vpn) manager.unregisterNetworkCallback(physicalCallback);
    }
}
