package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.ChargeRequest;
import com.xavier.backend.settlement.PaymentProvider;
import com.xavier.backend.settlement.ProviderOutcome;
import com.xavier.backend.shared.ExternalCalls;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every call to a {@link PaymentProvider} goes through here (D48, spec §7.3, §7.4): never inside a transaction
 * ({@link ExternalCalls#requireNoTransaction()} first), on a bounded pool, and bounded by
 * {@code restaurantpro.settlement.provider-timeout}. A call that does not answer in time, throws, or cannot even be
 * queued is {@link ProviderOutcome.Unknown}: settlement never treats it as a failure.
 *
 * <p>The timeout must be shorter than {@code restaurantpro.idempotency.step-lease}: a resolve waits for the provider
 * inside a D63 step, and a lease that expired during the call would let a duplicate request take the claim over
 * while the first one still runs. Startup fails otherwise.
 */
@Component
@EnableConfigurationProperties(SettlementProperties.class)
class ProviderCalls {

    private static final Logger log = LoggerFactory.getLogger(ProviderCalls.class);

    private final Duration timeout;
    private final ThreadPoolExecutor pool;

    ProviderCalls(SettlementProperties properties,
                  @Value("${restaurantpro.idempotency.step-lease:PT30S}") Duration stepLease) {
        this.timeout = requireBelowStepLease(properties.providerTimeout(), stepLease);
        int threads = properties.providerThreads();
        this.pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 25), Thread.ofPlatform().name("payment-provider-", 0).factory());
    }

    static Duration requireBelowStepLease(Duration providerTimeout, Duration stepLease) {
        if (providerTimeout.isNegative() || providerTimeout.isZero() || providerTimeout.compareTo(stepLease) >= 0) {
            throw new IllegalStateException("restaurantpro.settlement.provider-timeout (" + providerTimeout
                    + ") must be positive and shorter than restaurantpro.idempotency.step-lease (" + stepLease
                    + "): a resolve waits for the provider inside an idempotent step (D63)");
        }
        return providerTimeout;
    }

    ProviderOutcome charge(PaymentProvider provider, ChargeRequest request) {
        return call(provider, "charge", () -> provider.charge(request));
    }

    ProviderOutcome status(PaymentProvider provider, String reference) {
        return call(provider, "status", () -> provider.status(reference));
    }

    Duration timeout() {
        return timeout;
    }

    /**
     * Any other provider call (e.g. a statement fetch), on the same pool and bounded by the same timeout. Unlike a
     * charge or a status query it has no "unknown" outcome to record: a call that is not answered in time, or cannot
     * be queued, is {@link ProviderUnavailableException}; an exception of the call itself propagates.
     */
    <T> T within(String providerCode, String operation, Callable<T> call) {
        ExternalCalls.requireNoTransaction();
        Future<T> answer;
        try {
            answer = pool.submit(call);
        } catch (RejectedExecutionException e) {
            log.warn("Payment provider {} {}: too many calls in flight", providerCode, operation);
            throw new ProviderUnavailableException(providerCode, operation, "too many calls in flight");
        }
        try {
            return answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            answer.cancel(true);
            log.warn("Payment provider {} {}: no answer within {}", providerCode, operation, timeout);
            throw new ProviderUnavailableException(providerCode, operation, "no answer within " + timeout);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Payment provider " + providerCode + " " + operation + " failed",
                    e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            throw new ProviderUnavailableException(providerCode, operation, "interrupted");
        }
    }

    private ProviderOutcome call(PaymentProvider provider, String operation, Callable<ProviderOutcome> call) {
        ExternalCalls.requireNoTransaction();
        Future<ProviderOutcome> answer;
        try {
            answer = pool.submit(call);
        } catch (RejectedExecutionException e) {
            log.warn("Payment provider {} {}: too many calls in flight", provider.code(), operation);
            return new ProviderOutcome.Unknown("provider calls saturated");
        }
        try {
            ProviderOutcome outcome = answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return outcome == null ? new ProviderOutcome.Unknown("no answer") : outcome;
        } catch (TimeoutException e) {
            answer.cancel(true);
            log.warn("Payment provider {} {}: no answer within {}", provider.code(), operation, timeout);
            return new ProviderOutcome.Unknown("no answer within " + timeout);
        } catch (ExecutionException e) {
            log.warn("Payment provider {} {} failed; the outcome is unknown", provider.code(), operation,
                    e.getCause());
            return new ProviderOutcome.Unknown("provider error (" + e.getCause().getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            return new ProviderOutcome.Unknown("interrupted");
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }
}
