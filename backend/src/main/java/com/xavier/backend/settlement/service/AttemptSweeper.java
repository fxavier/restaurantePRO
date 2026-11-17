package com.xavier.backend.settlement.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.settlement.repository.Keyed;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D48's sweeper. A CREATED integrated attempt older than {@code created-grace} was never charged (the submitter
 * crashed or was full): it is submitted again. A PENDING one older than the provider timeout plus
 * {@code pending-margin} lost its caller during the call: it becomes UNKNOWN, never FAILED, and waits for a resolve.
 * Safe under overlap: submission is a compare-and-set, and expiry decides under the order's lock. One run takes at
 * most {@code restaurantpro.jobs.batch-per-tenant} attempts of each kind per tenant, continuing after the last one it
 * saw ({@link SweepCursors}), so a backlog that stays eligible never starves the newer attempts.
 */
@Service
public class AttemptSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptSweeper.class);

    private final PaymentAttemptRepository attempts;
    private final PaymentSubmitter submitter;
    private final AttemptTransitions transitions;
    private final SettlementProperties properties;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate inReadTransaction;
    private final Clock clock;
    private final int batch;
    private final TenantScopes tenants;
    private final SweepCursors cursors = new SweepCursors();

    AttemptSweeper(PaymentAttemptRepository attempts, PaymentSubmitter submitter, AttemptTransitions transitions,
                   SettlementProperties properties, PlatformTransactionManager transactionManager, Clock clock,
                   @Value("${restaurantpro.jobs.batch-per-tenant:100}") int batch, TenantScopes tenants) {
        this.batch = batch;
        this.tenants = tenants;
        this.attempts = attempts;
        this.submitter = submitter;
        this.transitions = transitions;
        this.properties = properties;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.inReadTransaction = new TransactionTemplate(transactionManager);
        this.inReadTransaction.setReadOnly(true);
        this.clock = clock;
    }

    @SystemScoped(SystemScope.PAYMENT_RECOVERY)
    @Scheduled(fixedDelayString = "${restaurantpro.settlement.sweep-interval:PT5S}",
            initialDelayString = "${restaurantpro.settlement.sweep-interval:PT5S}")
    public void sweep() {
        Instant now = clock.instant();
        UUID tenant = tenants.requireTenant();
        Duration lost = properties.providerTimeout().plus(properties.pendingMargin());
        // S77 (F4): both pages in one read-only transaction; the submissions call the provider, so after it ends
        List<List<Keyed>> due = inReadTransaction.execute(tx -> List.of(
                page(tenant, AttemptStatus.CREATED, now.minus(properties.createdGrace())),
                page(tenant, AttemptStatus.PENDING, now.minus(lost))));
        for (Keyed created : due.get(0)) {
            log.info("Payment attempt {} was never submitted; submitting it again", created.id());
            submitter.submit(created.id());
        }
        for (Keyed stale : due.get(1)) {
            UUID pending = stale.id();
            try {
                inTransaction.executeWithoutResult(tx -> transitions.expire(pending,
                        "no provider outcome within " + lost));
                log.warn("Payment attempt {} had no provider outcome within {}; it is UNKNOWN", pending, lost);
            } catch (RuntimeException e) {
                log.error("Payment attempt {} could not be marked UNKNOWN", pending, e);
            }
        }
    }

    private List<Keyed> page(UUID tenant, AttemptStatus status, Instant before) {
        Keyed after = cursors.after(tenant, status.name());
        List<Keyed> page = attempts.findStale(status, PaymentMethodKind.INTEGRATED, before, after.at(), after.id(),
                Pageable.ofSize(batch));
        cursors.read(tenant, status.name(), page, batch);
        return page;
    }
}
