package com.xavier.backend.settlement.service;

import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.PartitionStatus;
import com.xavier.backend.settlement.repository.BillPartitionRepository;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D49: the order's payment lock is released when every attempt ended FAILED or CANCELLED, nothing was paid, no
 * partition is LIVE (D67: the first partition took the lock) and no credit charge is live (D74), so a void or an
 * outgoing transfer is possible again. Called under the order's settlement lock.
 */
@Component
class PaymentLocks {

    private final PaymentAttemptRepository attempts;
    private final PaymentRepository payments;
    private final OrdersApi orders;
    private final BillPartitionRepository partitions;
    private final CreditChargeRepository charges;

    PaymentLocks(PaymentAttemptRepository attempts, PaymentRepository payments, OrdersApi orders,
                 BillPartitionRepository partitions, CreditChargeRepository charges) {
        this.charges = charges;
        this.partitions = partitions;
        this.attempts = attempts;
        this.payments = payments;
        this.orders = orders;
    }

    private static final java.util.Set<CreditChargeStatus> LIVE_CHARGES =
            java.util.EnumSet.of(CreditChargeStatus.RESERVED, CreditChargeStatus.POSTED);

    @Transactional(propagation = Propagation.MANDATORY)
    void releaseIfIdle(UUID orderId) {
        if (orderId == null) {
            return; // a debt payment (D75) pays no order, so it never took an order's payment lock
        }
        if (!attempts.existsByOrderIdAndStatusIn(orderId, AttemptStatus.OPEN) && !payments.existsByOrderId(orderId)
                && !partitions.existsByOrderIdAndStatus(orderId, PartitionStatus.LIVE)
                && !charges.existsByOrderIdAndStatusIn(orderId, LIVE_CHARGES)) {
            orders.releasePaymentLock(orderId);
        }
    }
}
