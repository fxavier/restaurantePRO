package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.dto.SettlementAlertsView;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The POS alert of settlement for the session's store: the attempts in doubt, for who collects payments (and sees
 * them on the bill) or resolves them. Production's alerts are their own read; the POS composes them.
 */
@Service
public class SettlementAlertsService {

    private final AccessPolicy accessPolicy;
    private final PaymentAttemptRepository attempts;

    SettlementAlertsService(AccessPolicy accessPolicy, PaymentAttemptRepository attempts) {
        this.accessPolicy = accessPolicy;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public SettlementAlertsView alertsForPos() {
        PosContext pos = accessPolicy.requirePosAny(Permission.PAYMENT_COLLECT, Permission.PAYMENT_RESOLVE);
        long inDoubt = attempts.countByStoreIdAndStatus(pos.storeId(), AttemptStatus.UNKNOWN);
        if (inDoubt == 0) {
            return new SettlementAlertsView(0, null);
        }
        return new SettlementAlertsView(inDoubt, attempts
                .findFirstByStoreIdAndStatusAndOrderIdNotNullOrderByCreatedAt(pos.storeId(), AttemptStatus.UNKNOWN)
                .map(PaymentAttempt::getOrderId).orElse(null));
    }
}
