package com.xavier.backend.settlement.service;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.settlement.ProviderOutcome;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D48 "persist, then call, then record" for integrated attempts, after the commit that created them: on a bounded
 * executor, tx-a moves the attempt CREATED → PENDING (exactly one submitter calls), the provider is charged with no
 * transaction and no lock, and tx-b records the outcome (a timeout is UNKNOWN). A full queue or a crash leaves the
 * attempt CREATED (never charged), which the sweeper submits again.
 */
@Component
public class PaymentSubmitter {

    private static final Logger log = LoggerFactory.getLogger(PaymentSubmitter.class);

    private final AttemptTransitions transitions;
    private final PaymentProviders providers;
    private final ProviderCalls calls;
    private final TransactionTemplate inTransaction;
    private final StoreScopes scopes;
    private final ThreadPoolExecutor pool;

    PaymentSubmitter(AttemptTransitions transitions, PaymentProviders providers, ProviderCalls calls,
                     SettlementProperties properties, PlatformTransactionManager transactionManager,
                     StoreScopes scopes) {
        this.scopes = scopes;
        this.transitions = transitions;
        this.providers = providers;
        this.calls = calls;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.inTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int threads = properties.providerThreads();
        this.pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 25), Thread.ofPlatform().name("payment-submitter-", 0).factory());
    }

    /** Queues the attempt for submission; never blocks the caller. */
    public void submit(UUID attemptId) {
        try {
            // the submitting POS session's store scope, or PAYMENT_RECOVERY from the sweeper (D166)
            pool.execute(scopes.propagate(() -> run(attemptId)));
        } catch (RejectedExecutionException e) {
            log.warn("Payment submitter is full; attempt {} stays CREATED for the sweeper", attemptId);
        }
    }

    private void run(UUID attemptId) {
        try {
            Optional<AttemptTransitions.Submission> submission = inTransaction.execute(tx ->
                    transitions.submit(attemptId));
            if (submission == null || submission.isEmpty()) {
                return;
            }
            AttemptTransitions.Submission charge = submission.get();
            ProviderOutcome outcome = providers.find(charge.providerCode())
                    .map(provider -> calls.charge(provider, charge.charge()))
                    .orElseGet(() -> new ProviderOutcome.Unknown("no provider " + charge.providerCode()));
            inTransaction.executeWithoutResult(tx -> transitions.recordCharge(attemptId, outcome));
        } catch (RuntimeException e) {
            // tx-a failed: still CREATED (the sweeper resubmits); tx-b failed: still PENDING (the sweeper → UNKNOWN)
            log.error("Payment attempt {} could not be submitted or recorded", attemptId, e);
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }
}
