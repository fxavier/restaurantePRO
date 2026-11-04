package com.xavier.backend.settlement.service;

import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D49: the order's payment lock is released when every attempt ended FAILED or CANCELLED and nothing was paid, so a
 * void or an outgoing transfer is possible again. Called under the order's settlement lock.
 */
@Component
class PaymentLocks {

    private final PaymentAttemptRepository attempts;
    private final PaymentRepository payments;
    private final OrdersApi orders;

    PaymentLocks(PaymentAttemptRepository attempts, PaymentRepository payments, OrdersApi orders) {
        this.attempts = attempts;
        this.payments = payments;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void releaseIfIdle(UUID orderId) {
        if (!attempts.existsByOrderIdAndStatusIn(orderId, AttemptStatus.OPEN) && !payments.existsByOrderId(orderId)) {
            orders.releasePaymentLock(orderId);
        }
    }
}
