package com.gridlens.android;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Fixed-size candidate workers, separate from authentication and reusable in isolated fixtures. */
final class DiscoveryScanner {
    interface Probe { boolean test(DiscoveryPolicy.Candidate candidate); }
    interface Progress { void update(int completed, int total, int found); }
    static List<DiscoveryPolicy.Candidate> scan(List<String> addresses, List<Integer> ports,
            Probe probe, BooleanSupplier cancelled, Progress progress) throws IOException {
        return scan(Math.multiplyExact(addresses.size(), ports.size()), item -> new DiscoveryPolicy.Candidate(
                addresses.get(item / ports.size()), ports.get(item % ports.size()), ""), probe, cancelled, progress);
    }
    static List<DiscoveryPolicy.Candidate> scanCandidates(List<DiscoveryPolicy.Candidate> candidates,
            Probe probe, BooleanSupplier cancelled, Progress progress) throws IOException {
        return scan(candidates.size(), candidates::get, probe, cancelled, progress);
    }
    private static List<DiscoveryPolicy.Candidate> scan(int total, java.util.function.IntFunction<DiscoveryPolicy.Candidate> candidateAt,
            Probe probe, BooleanSupplier cancelled, Progress progress) throws IOException {
        Map<String, DiscoveryPolicy.Candidate> found = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicInteger next = new AtomicInteger(), completed = new AtomicInteger();
        AtomicBoolean overflow = new AtomicBoolean();
        ExecutorService executor = Executors.newFixedThreadPool(DiscoveryPolicy.PROBE_CONCURRENCY);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < DiscoveryPolicy.PROBE_CONCURRENCY; worker++) tasks.add(executor.submit(() -> {
                while (!cancelled.getAsBoolean() && !overflow.get()) {
                    int item = next.getAndIncrement();
                    if (item >= total) return;
                    DiscoveryPolicy.Candidate candidate = candidateAt.apply(item);
                    if (probe.test(candidate)) synchronized (found) {
                        if (found.size() >= 2048) { overflow.set(true); return; }
                        found.put(candidate.endpointKey(), candidate);
                    }
                    int done = completed.incrementAndGet();
                    if (progress != null && (done % 32 == 0 || done == total)) progress.update(done, total, found.size());
                }
            }));
            for (Future<?> task : tasks) task.get();
            if (overflow.get()) throw new IOException("Too many SSH candidates; narrow the network profile");
            if (cancelled.getAsBoolean()) throw new IOException("Discovery cancelled");
            return new ArrayList<>(found.values());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Discovery cancelled"); }
        catch (ExecutionException e) { throw new IOException("Network search failed"); }
        finally { executor.shutdownNow(); }
    }
}
