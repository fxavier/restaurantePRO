package com.xavier.backend.inventory;

import com.xavier.backend.access.StoreScopes;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A fixed pool whose tasks run in the store scope of the thread that submits them ({@link StoreScopes#propagate}, the
 * production rule for executor submissions, ADR 0039). The concurrency tests submit from the JUnit thread, whose
 * fixture scope is every store; without this their workers would have production's unset scope ({@code RS001}).
 */
public final class ScopedPool extends AbstractExecutorService {

    private final ExecutorService delegate;
    private final StoreScopes scopes;

    private ScopedPool(ExecutorService delegate, StoreScopes scopes) {
        this.delegate = delegate;
        this.scopes = scopes;
    }

    public static ExecutorService of(StoreScopes scopes, int threads) {
        return new ScopedPool(Executors.newFixedThreadPool(threads), scopes);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(scopes.propagate(command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
