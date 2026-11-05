package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PartitionRef;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D74 recovery: a credit sale that stopped after its document was issued (a crash between s2 and s3) still holds a
 * RESERVED charge. Every {@code restaurantpro.settlement.credit-sweep-interval} (PT1M) this posts the debit of each
 * RESERVED charge older than {@code credit-sweep-grace} (PT1M; a younger one belongs to a sale still running its
 * steps) whose document is ISSUED, one short transaction per charge, audited as the system. It takes the
 * order's settlement lock with {@code SKIP LOCKED} (D46), so it never waits behind a request (which posts the charge
 * itself) and keeps the D76 lock order; a skipped charge is taken next time. Posting is idempotent
 * ({@code CustomersApi.postDebit} per charge), so an overlap with the sale's own s3 adds nothing.
 */
@Service
public class CreditChargeSweeper {

    private static final Logger log = LoggerFactory.getLogger(CreditChargeSweeper.class);

    private final CreditChargeRepository charges;
    private final SettlementLocks locks;
    private final CreditSaleSteps steps;
    private final TransactionTemplate inTransaction;
    private final SettlementProperties properties;
    private final Clock clock;

    CreditChargeSweeper(CreditChargeRepository charges, SettlementLocks locks, CreditSaleSteps steps,
                        PlatformTransactionManager transactionManager, SettlementProperties properties,
                        Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.charges = charges;
        this.locks = locks;
        this.steps = steps;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${restaurantpro.settlement.credit-sweep-interval:PT1M}",
            initialDelayString = "${restaurantpro.settlement.credit-sweep-interval:PT1M}")
    public void sweep() {
        for (UUID chargeId : charges.findReservedWithDocument(clock.instant().minus(properties.creditSweepGrace()))) {
            try {
                inTransaction.executeWithoutResult(tx -> postIfIssued(chargeId));
            } catch (RuntimeException e) {
                log.error("Credit charge {} could not be posted; it stays RESERVED for the next sweep", chargeId, e);
            }
        }
    }

    private void postIfIssued(UUID chargeId) {
        PartitionRef ref = charges.findRefById(chargeId).orElseThrow();
        if (!locks.tryLockOrder(ref.orderId())) {
            return;
        }
        CreditCharge charge = charges.findById(chargeId).orElseThrow(); // read under the lock
        if (charge.getStatus() == CreditChargeStatus.RESERVED && steps.documentIssued(charge)) {
            steps.postLocked(charge, null);
            log.info("Credit charge {} was posted by the sweeper", chargeId);
        }
    }
}
