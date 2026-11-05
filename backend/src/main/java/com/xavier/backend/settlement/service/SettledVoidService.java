package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.SettledVoidCommand;
import com.xavier.backend.orders.VoidView;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.dto.SettledVoidRequest;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The coordinated void within headroom (D71, P32), with an approver (D40's two phases). It works under billing lock
 * NONE or PAYMENT; R2's orders route keeps refusing under PAYMENT. Phase 2 is one transaction: the order's store
 * (D42, nothing loaded before the 403) → {@code order_settlement FOR UPDATE} → the headroom checks, on the bill and
 * {@code FiscalApi.coverage} as committed → {@code OrdersApi.voidLine}, which keeps the void fact, its event (the VOID
 * ticket) and its audit, and may bypass the PAYMENT lock only because this caller holds the lock and checked.
 */
@Service
public class SettledVoidService {

    private final AccessPolicy accessPolicy;
    private final ApproverVerifier approvers;
    private final SettlementLocks locks;
    private final Bills bills;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final SettlementAudit audit;

    SettledVoidService(AccessPolicy accessPolicy, ApproverVerifier approvers, SettlementLocks locks, Bills bills,
                       OrdersApi orders, FiscalApi fiscal, SettlementAudit audit) {
        this.accessPolicy = accessPolicy;
        this.approvers = approvers;
        this.locks = locks;
        this.bills = bills;
        this.orders = orders;
        this.fiscal = fiscal;
        this.audit = audit;
    }

    /** Who vouched for a void of {@code orderId}, checked before any transaction (D40 phase 1). */
    public record VoidApproval(UUID orderId, UUID approverEmployeeId) {
    }

    /**
     * D40 phase 1, outside any transaction: {@code ORDER_VOID} on the order's store (404, 403), then an approver
     * holding {@code ORDER_VOID_APPROVE} there who is not the operator.
     *
     * @throws ApprovalDeniedException 403 for missing or refused approver credentials
     * @throws SettlementRuleException 422 {@code SELF_APPROVAL}
     */
    public VoidApproval authorize(UUID orderId, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VOID);
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        UUID approver = approvers.verify(approverCode, approverPin, Permission.ORDER_VOID_APPROVE, pos.storeId());
        if (approver.equals(pos.employeeId())) {
            throw new SettlementRuleException(SettlementRule.SELF_APPROVAL,
                    "The approver of a void cannot be the operator.");
        }
        return new VoidApproval(orderId, approver);
    }

    /**
     * D40 phase 2: the headroom checks, then the void.
     *
     * @throws SettlementRuleException 422 {@code ORDER_NOT_OPEN}, {@code CORRECTION_REQUIRES_FISCAL_ADAPTER},
     *         {@code QUANTITY_EXCEEDS_NET}, {@code QUANTITY_PARTITIONED}, {@code VOID_EXCEEDS_UNPAID},
     *         {@code SPLIT_ROUNDING_LIMIT}
     */
    @Transactional
    public VoidView voidLine(UUID orderId, SettledVoidRequest request, VoidApproval approval) {
        if (!approval.orderId().equals(orderId)) {
            throw new IllegalArgumentException("The approval is for order " + approval.orderId());
        }
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VOID);
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        locks.lockOrder(orderId);
        Bill bill = bills.of(orderId).requireOpen();
        requireNotInvoiced(bill, request);
        BillingLine line = bill.line(request.lineId()).orElseThrow(() -> new SettlementRuleException(
                SettlementRule.QUANTITY_EXCEEDS_NET, "Line " + request.lineId() + " has nothing billable.",
                Map.of("lineId", request.lineId())));
        Money reduction = bill.split(line).voidReduces(request.quantity());
        requireHeadroom(bill, reduction);
        VoidView voided = orders.voidLine(new SettledVoidCommand(orderId, request.orderVersion(), request.lineId(),
                request.quantity(), request.reason(), request.wasted(), pos.employeeId()),
                approval.approverEmployeeId());
        audit.orderChanged(bill.storeId(), orderId);
        return voided;
    }

    /** P31: an invoiced quantity, or any line of a frozen order, is corrected by a document, not a void. */
    private void requireNotInvoiced(Bill bill, SettledVoidRequest request) {
        if (bill.frozen()) {
            throw correction("Order " + bill.view().orderId() + " has its document");
        }
        Coverage coverage = fiscal.coverage(bill.view().orderId());
        BigDecimal covered = SettlementViews.quantities(coverage.issued()).getOrDefault(request.lineId(),
                BigDecimal.ZERO).add(SettlementViews.quantities(coverage.draft()).getOrDefault(request.lineId(),
                BigDecimal.ZERO));
        BigDecimal net = bill.line(request.lineId()).map(BillingLine::netQty).orElse(BigDecimal.ZERO);
        if (covered.signum() > 0 && request.quantity().compareTo(net.subtract(covered)) > 0) {
            throw correction("Only " + net.subtract(covered).stripTrailingZeros().toPlainString() + " of line "
                    + request.lineId() + " is on no document");
        }
    }

    /** D71: the ORDER residual after the void stays at or above what its target was paid or has in flight. */
    private static void requireHeadroom(Bill bill, Money reduction) {
        Money residualAfter = bill.residualDue().minus(reduction);
        Money owed = bill.openOn(null).stream().map(PaymentAttempt::amount).reduce(bill.orderPaid(), Money::plus);
        if (residualAfter.amount().compareTo(owed.amount()) < 0) {
            throw new SettlementRuleException(SettlementRule.VOID_EXCEEDS_UNPAID, "The void would leave "
                    + residualAfter.amount() + " due on order " + bill.view().orderId() + ", less than the "
                    + owed.amount() + " paid or being paid: refund first.", Map.of("unpaid",
                    bill.residualDue().minus(owed).amount()));
        }
    }

    private static SettlementRuleException correction(String what) {
        return new SettlementRuleException(SettlementRule.CORRECTION_REQUIRES_FISCAL_ADAPTER, what
                + ": correcting invoiced quantities needs the market's fiscal adapter.");
    }
}
