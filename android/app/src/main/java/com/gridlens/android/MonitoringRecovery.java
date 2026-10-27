package com.gridlens.android;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** One explicit retry of saved approvals, without changing their source permissions. */
final class MonitoringRecovery {
    static boolean eligible(boolean approved, boolean paused, String status, boolean aliasReplaced) {
        return approved && paused && "credentials-required".equals(status) && !aliasReplaced;
    }
    interface Verifier<T> { String verify(T target) throws Exception; }
    static final class Result<T> {
        final T target; final String status; final boolean attempted;
        Result(T target, String status, boolean attempted) { this.target = target; this.status = status; this.attempted = attempted; }
    }
    static <T> List<Result<T>> check(List<T> targets, Function<T, String> credential, Verifier<T> verifier,
            BooleanSupplier cancelled, Consumer<Result<T>> completed, Runnable cancelAll) throws Exception {
        if (targets.size() > 512) throw new IllegalArgumentException("Too many saved connections");
        List<Result<T>> results = Collections.synchronizedList(new ArrayList<>());
        LinkedHashMap<String, List<T>> grouped = new LinkedHashMap<>();
        for (T target : targets) grouped.computeIfAbsent(credential.apply(target), ignored -> new ArrayList<>()).add(target);
        List<List<T>> groups = new ArrayList<>(grouped.values());
        AtomicInteger next = new AtomicInteger(); AtomicBoolean stopped = new AtomicBoolean();
        AtomicReference<Exception> failure = new AtomicReference<>();
        ExecutorService workers = Executors.newFixedThreadPool(MonitoringPolicy.MAX_CONCURRENT_PCS);
        List<Future<?>> jobs = new ArrayList<>();
        try {
            for (int worker = 0; worker < MonitoringPolicy.MAX_CONCURRENT_PCS; worker++) jobs.add(workers.submit(() -> {
                while (!stopped.get() && !cancelled.getAsBoolean()) {
                    int index = next.getAndIncrement(); if (index >= groups.size()) return;
                    boolean rejected = false;
                    // One worker owns each credential profile. A rejected password cannot be
                    // dispatched to another PC after that rejection becomes known.
                    for (T target : groups.get(index)) {
                        if (cancelled.getAsBoolean() || stopped.get()) return;
                        boolean attempted = !rejected;
                        String status;
                        try { status = attempted ? verifier.verify(target) : "credentials-required"; }
                        catch (Exception unavailable) { status = "unreachable"; }
                        if ("credentials-required".equals(status)) rejected = true;
                        Result<T> result = new Result<>(target, status, attempted); results.add(result);
                        try { completed.accept(result); }
                        catch (RuntimeException error) {
                            failure.compareAndSet(null, error); stopped.set(true); cancelAll.run(); return;
                        }
                    }
                }
            }));
            for (Future<?> job : jobs) job.get();
            if (failure.get() != null) throw failure.get();
            return new ArrayList<>(results);
        } finally { stopped.set(true); workers.shutdownNow(); }
    }
    static <T> List<T> verified(List<Result<T>> results, Function<T, String> credential) {
        Set<String> rejected = new HashSet<>();
        for (Result<T> result : results) if ("credentials-required".equals(result.status)) rejected.add(credential.apply(result.target));
        List<T> verified = new ArrayList<>();
        for (Result<T> result : results) if (result.attempted && "active".equals(result.status) && !rejected.contains(credential.apply(result.target))) verified.add(result.target);
        return verified;
    }
    private MonitoringRecovery() {}
}
