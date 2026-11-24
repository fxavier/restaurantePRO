package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.settlement.model.dto.SettlementAlertsView;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The POS alert of settlement for the session's store: the attempts in doubt, for who collects payments (and sees
 * them on the bill) or resolves them. Production's alerts are their own read; the POS composes them.
 */
@Service
public class SettlementAlertsService {

    private static final Pageable FIRST = PageRequest.of(0, 1);

    private final AccessPolicy accessPolicy;
    private final PaymentAttemptRepository attempts;

    SettlementAlertsService(AccessPolicy accessPolicy, PaymentAttemptRepository attempts) {
        this.accessPolicy = accessPolicy;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public SettlementAlertsView alertsForPos() {
        PosContext pos = accessPolicy.requirePosAny(Permission.PAYMENT_COLLECT, Permission.PAYMENT_RESOLVE);
        long inDoubt = attempts.countInDoubtInStore(pos.storeId());
        if (inDoubt == 0) {
            return new SettlementAlertsView(0, null);
        }
        return new SettlementAlertsView(inDoubt, attempts.findInDoubtOrdersOfStore(pos.storeId(), FIRST).stream()
                .findFirst().orElse(null));
    }
}
