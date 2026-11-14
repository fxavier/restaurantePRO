package com.xavier.backend.settlement.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D48's sweeper. A CREATED integrated attempt older than {@code created-grace} was never charged (the submitter
 * crashed or was full): it is submitted again. A PENDING one older than the provider timeout plus
 * {@code pending-margin} lost its caller during the call: it becomes UNKNOWN, never FAILED, and waits for a resolve.
 * Safe under overlap: submission is a compare-and-set, and expiry decides under the order's lock.
 */
@Service
public class AttemptSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptSweeper.class);

    private final PaymentAttemptRepository attempts;
    private final PaymentSubmitter submitter;
    private final AttemptTransitions transitions;
    private final SettlementProperties properties;
    private final TransactionTemplate inTransaction;
    private final Clock clock;

    AttemptSweeper(PaymentAttemptRepository attempts, PaymentSubmitter submitter, AttemptTransitions transitions,
                   SettlementProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.attempts = attempts;
        this.submitter = submitter;
        this.transitions = transitions;
        this.properties = properties;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @SystemScoped(SystemScope.PAYMENT_RECOVERY)
    @Scheduled(fixedDelayString = "${restaurantpro.settlement.sweep-interval:PT5S}",
            initialDelayString = "${restaurantpro.settlement.sweep-interval:PT5S}")
    public void sweep() {
        Instant now = clock.instant();
        for (UUID created : attempts.findStale(AttemptStatus.CREATED, PaymentMethodKind.INTEGRATED,
                now.minus(properties.createdGrace()))) {
            log.info("Payment attempt {} was never submitted; submitting it again", created);
            submitter.submit(created);
        }
        Duration lost = properties.providerTimeout().plus(properties.pendingMargin());
        for (UUID pending : attempts.findStale(AttemptStatus.PENDING, PaymentMethodKind.INTEGRATED, now.minus(lost))) {
            try {
                inTransaction.executeWithoutResult(tx -> transitions.expire(pending,
                        "no provider outcome within " + lost));
                log.warn("Payment attempt {} had no provider outcome within {}; it is UNKNOWN", pending, lost);
            } catch (RuntimeException e) {
                log.error("Payment attempt {} could not be marked UNKNOWN", pending, e);
            }
        }
    }
}
