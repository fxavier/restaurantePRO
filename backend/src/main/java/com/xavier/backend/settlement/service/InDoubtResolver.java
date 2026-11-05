package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D81 (2): automatic polling of payments in doubt (§7.4 "consultar antes de repetir"). Every
 * {@code restaurantpro.settlement.in-doubt-poll-interval} (PT30S) it asks the provider about each UNKNOWN attempt that
 * is due (a minute after it became UNKNOWN, then after 2, 5, 10 and 30 minutes, then every hour), with the resolve's
 * own D63 steps and no request: s1 reads the attempt, s2 asks the provider with no transaction, s3 records the answer
 * under the target's lock through {@link AttemptTransitions#resolve}, audited as the system. A final answer is the
 * same compare-and-set as the operator's resolve, so there is still exactly one Payment; an overlap with it, or with
 * another run, at most counts one more poll.
 */
@Service
public class InDoubtResolver {

    private static final Logger log = LoggerFactory.getLogger(InDoubtResolver.class);

    private final PaymentAttemptRepository attempts;
    private final ProviderStatusQuery statusQuery;
    private final AttemptTransitions transitions;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    InDoubtResolver(PaymentAttemptRepository attempts, ProviderStatusQuery statusQuery,
                    AttemptTransitions transitions, PlatformTransactionManager transactionManager, Clock clock) {
        this.attempts = attempts;
        this.statusQuery = statusQuery;
        this.transitions = transitions;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${restaurantpro.settlement.in-doubt-poll-interval:PT30S}",
            initialDelayString = "${restaurantpro.settlement.in-doubt-poll-interval:PT30S}")
    public void poll() {
        for (UUID attemptId : attempts.findDueForPoll(clock.instant())) {
            try {
                pollOne(attemptId);
            } catch (RuntimeException e) {
                log.error("Payment attempt {} could not be polled; it stays UNKNOWN for the next run", attemptId, e);
            }
        }
    }

    private void pollOne(UUID attemptId) {
        PaymentAttempt read = readOnly.execute(tx -> attempts.findById(attemptId).orElseThrow());
        if (read.getStatus() != AttemptStatus.UNKNOWN) {
            return;
        }
        ProviderStatusQuery.Answer answer = statusQuery.ask(read);
        PaymentAttempt after = inTransaction.execute(tx -> transitions.resolve(attemptId, answer, null));
        if (after.getStatus() != AttemptStatus.UNKNOWN) {
            log.info("Payment attempt {} was resolved by polling: {}", attemptId, after.getStatus());
        }
    }
}
