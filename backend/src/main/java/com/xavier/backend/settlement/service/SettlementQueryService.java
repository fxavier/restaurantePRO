package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.FinancialState;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.model.dto.FiscalDraftView;
import com.xavier.backend.settlement.model.dto.OpenOrderBalance;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.model.dto.PendingView;
import com.xavier.backend.settlement.repository.PaidTotal;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.repository.RefundRepository;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The reads of settlement: an order's settlement, the open orders' balances, and a store's pendings. */
@Service
public class SettlementQueryService {

    private final AccessPolicy accessPolicy;
    private final Bills bills;
    private final SettlementViews views;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final CreditChargeRepository charges;
    private final RefundRepository refunds;

    SettlementQueryService(AccessPolicy accessPolicy, Bills bills, SettlementViews views, OrdersApi orders,
                           FiscalApi fiscal, PaymentRepository payments, PaymentAttemptRepository attempts,
                           CreditChargeRepository charges, RefundRepository refunds) {
        this.refunds = refunds;
        this.charges = charges;
        this.accessPolicy = accessPolicy;
        this.bills = bills;
        this.views = views;
        this.orders = orders;
        this.fiscal = fiscal;
        this.payments = payments;
        this.attempts = attempts;
    }

    @Transactional(readOnly = true)
    public OrderSettlementView settlement(UUID orderId) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        return views.of(bills.of(orderId));
    }

    /** The POS session's store: every OPEN order with what is due, paid and left, oldest first. */
    @Transactional(readOnly = true)
    public List<OpenOrderBalance> openOrders() {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        return balances(pos.storeId());
    }

    /** BackOffice, {@code PAYMENT_RESOLVE} in the store (D42: the store is an input, so it is checked first). */
    @Transactional(readOnly = true)
    public PendingView pending(UUID storeId) {
        accessPolicy.require(Permission.PAYMENT_RESOLVE, storeId);
        List<OrderBillingView> open = orders.openBillingViews(storeId);
        List<FiscalDraftView> drafts = open.stream()
                .flatMap(order -> fiscal.documentsOfOrder(order.orderId()).stream())
                .filter(document -> "DRAFT".equals(document.status()))
                .map(document -> new FiscalDraftView(document.id(), document.orderId(), document.lastError(),
                        document.lastErrorAt(), document.sourceType(), document.sourceId()))
                .toList();
        List<OpenOrderBalance> settledOpen = balances(open).stream()
                .filter(order -> order.due().amount().signum() > 0
                        && FinancialState.SETTLED.name().equals(order.financialState()))
                .toList();
        List<AttemptView> inDoubt = attempts.findByStoreIdAndStatusInOrderByCreatedAt(storeId, AttemptStatus.OPEN)
                .stream().map(PaymentAttempt::toView).toList();
        List<CreditChargeView> reserved = charges.findByStoreIdAndStatusOrderByCreatedAt(storeId,
                CreditChargeStatus.RESERVED).stream().map(CreditCharge::toView).toList();
        return new PendingView(storeId, inDoubt, drafts, settledOpen, reserved);
    }

    private List<OpenOrderBalance> balances(UUID storeId) {
        return balances(orders.openBillingViews(storeId));
    }

    private List<OpenOrderBalance> balances(List<OrderBillingView> open) {
        if (open.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = open.stream().map(OrderBillingView::orderId).toList();
        Map<UUID, BigDecimal> paid = payments.paidFor(ids).stream()
                .collect(Collectors.toMap(PaidTotal::orderId, PaidTotal::paid, BigDecimal::add,
                        java.util.HashMap::new));
        refunds.refundedFor(ids).forEach(refunded -> paid.merge(refunded.orderId(), refunded.paid().negate(),
                BigDecimal::add)); // D77: paid is net of refunds
        Map<UUID, BigDecimal> credit = charges.creditFor(ids).stream()
                .collect(Collectors.toMap(PaidTotal::orderId, PaidTotal::paid));
        return open.stream().map(order -> {
            Money paidSoFar = Money.of(paid.getOrDefault(order.orderId(), BigDecimal.ZERO), order.due().currency());
            Money onAccount = Money.of(credit.getOrDefault(order.orderId(), BigDecimal.ZERO), order.due().currency());
            Money covered = paidSoFar.plus(onAccount);
            return new OpenOrderBalance(order.orderId(), order.due(), paidSoFar, order.due().minus(covered),
                    FinancialState.of(order.due(), covered).name(), order.tableId(), order.tableCode(), onAccount,
                    order.mode().name(), order.customerId(), order.delivery());
        }).toList();
    }
}
