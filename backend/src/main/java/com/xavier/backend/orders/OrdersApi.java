package com.xavier.backend.orders;

import com.xavier.backend.orders.service.LineVoidService;
import com.xavier.backend.orders.service.OrderCoordinationService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What settlement asks the orders module (D36, D37, D49). Writes are {@code MANDATORY}: settlement composes them in
 * its own transaction, under its {@code order_settlement} lock (D51). Nothing here authorizes; the calling use case
 * already did (D27). Each write that changes the order increments its version at once, so a void, transfer or round
 * that read the order before loses with 409; a write that changes nothing (already locked, already frozen) is a no-op.
 *
 * <p>Errors: an unknown order → {@link com.xavier.backend.shared.exception.NotFoundException} (404); an order that is
 * not OPEN → {@link com.xavier.backend.shared.exception.ConflictException} {@code ORDER_NOT_OPEN} (409); a stale
 * {@code expectedVersion} → {@link org.springframework.dao.OptimisticLockingFailureException} (409).
 */
@Service
public class OrdersApi {

    private final OrderCoordinationService coordination;
    private final LineVoidService voids;

    OrdersApi(OrderCoordinationService coordination, LineVoidService voids) {
        this.coordination = coordination;
        this.voids = voids;
    }

    /**
     * Only the order's store, for D42 (b): read it, authorize against it, and only then lock or load the order. Its
     * store never changes, so it may be read before the lock.
     */
    @Transactional(readOnly = true)
    public UUID storeOf(UUID orderId) {
        return coordination.storeOf(orderId);
    }

    /** The billable lines (net quantity {@code > 0}) and due of the order, in line creation order. */
    @Transactional(readOnly = true)
    public OrderBillingView billingView(UUID orderId) {
        return coordination.billingView(orderId);
    }

    /** The billing views of the store's OPEN orders, oldest first. */
    @Transactional(readOnly = true)
    public List<OrderBillingView> openBillingViews(UUID storeId) {
        return coordination.openBillingViews(storeId);
    }

    /**
     * NONE → PAYMENT, in the transaction that creates the first payment attempt: voids and outgoing transfers are
     * refused from now on (422 {@code ORDER_IN_SETTLEMENT}, S14); new rounds stay allowed. No-op when already locked.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForPayment(UUID orderId) {
        coordination.lockForPayment(orderId);
    }

    /** PAYMENT → NONE, when every attempt ended FAILED or CANCELLED and nothing was paid. No-op otherwise. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releasePaymentLock(UUID orderId) {
        coordination.releasePaymentLock(orderId);
    }

    /**
     * → INVOICE, when the document draft is created: every later change is refused (422 {@code ORDER_FROZEN}). Never
     * released in R2. No-op when already frozen (D63 step s1 is retried).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void freezeForInvoice(UUID orderId) {
        coordination.freezeForInvoice(orderId);
    }

    /**
     * OPEN → CLOSED at {@code expectedVersion}, closing the table occupation through {@code DiningApi} for a DINE_IN
     * order. Settlement checked balance, attempts and document first (D50 §7.8). Needs an authenticated session (the
     * occupation close and the audit record the current actor); {@code actorEmployeeId} is stored as the closer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void close(UUID orderId, long expectedVersion, UUID actorEmployeeId) {
        coordination.close(orderId, expectedVersion, actorEmployeeId);
    }

    /**
     * The coordinated void (D71, P32). <strong>Contract:</strong> the caller holds the order's
     * {@code order_settlement} lock and has checked the headroom (the quantity is in no live partition and on no
     * document, and the bill stays at or above what was paid or put on account), so this bypasses the PAYMENT lock;
     * INVOICE still refuses (422 {@code ORDER_FROZEN}). It claims the order at {@code expectedVersion}, records the
     * void fact, publishes {@code OrderLinesVoided} (production's VOID ticket) and audits it exactly like the R2 void,
     * in a POS session (its terminal is the void's).
     *
     * @throws com.xavier.backend.orders.exception.OrderRuleException 422 {@code ORDER_FROZEN},
     *                                                                {@code QUANTITY_EXCEEDS_NET}, {@code SELF_APPROVAL}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public VoidView voidLine(SettledVoidCommand cmd, UUID approverEmployeeId) {
        return voids.voidSettled(cmd, approverEmployeeId);
    }

    /** OPEN orders of the store whose business day is on or before {@code businessDayInclusive} (day close). */
    @Transactional(readOnly = true)
    public long countOpen(UUID storeId, LocalDate businessDayInclusive) {
        return coordination.countOpen(storeId, businessDayInclusive);
    }
}
