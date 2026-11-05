package com.xavier.backend.settlement.service;

import com.xavier.backend.customers.CustomersApi;
import com.xavier.backend.settlement.repository.AttemptRef;
import com.xavier.backend.settlement.repository.OrderSettlementRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order's settlement lock (D51): the first lock of every transaction that pays, issues or closes an order, always
 * before {@code cash_session} and fiscal. Take it before reading anything of the order in that transaction, so the
 * reads see what the previous holder committed.
 */
@Component
class SettlementLocks {

    private final OrderSettlementRepository rows;
    private final CustomersApi customers;
    private final Clock clock;

    SettlementLocks(OrderSettlementRepository rows, CustomersApi customers, Clock clock) {
        this.rows = rows;
        this.customers = customers;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void lockOrder(UUID orderId) {
        rows.createIfAbsent(orderId, clock.instant());
        rows.lock(orderId);
    }

    /**
     * The first lock of a command on an attempt (D76): its order's settlement lock, or, for a debt payment, its
     * customer's {@code credit_account}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void lockTargetOf(AttemptRef attempt) {
        if (attempt.creditAccountId() != null) {
            customers.lockAccount(attempt.creditAccountId());
        } else {
            lockOrder(attempt.orderId());
        }
    }

    /** {@link #lockOrder} for a background job (D46): false, without waiting, while a request holds the order. */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean tryLockOrder(UUID orderId) {
        rows.createIfAbsent(orderId, clock.instant());
        return rows.tryLock(orderId) != null;
    }
}
