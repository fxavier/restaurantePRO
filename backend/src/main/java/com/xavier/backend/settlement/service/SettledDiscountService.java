package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.AdjustLine;
import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.SettledAdjustmentCommand;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.model.dto.SettledDiscountRequest;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A discount after confirmation under the PAYMENT lock (D105), the D71 pattern: settlement holds the order's
 * {@code order_settlement} lock, checks what orders cannot see (partitions, documents, payments) and then calls
 * {@code OrdersApi.adjustLinePrice}, which applies the D106 limits, splits a partial quantity, redeems a reward and
 * refuses a REVERSAL, exactly like the orders route. Phase 1 (D40) is outside any transaction: the kind's permission
 * on the order's store (404, 403), then the approver, when the POS sent one. Phase 2 is one transaction: the order's
 * store (D42) → {@code order_settlement FOR UPDATE} → per line, none of it on a document (or the order frozen) and none
 * in a LIVE partition → the adjustment (D132: {@code order_header}, then {@code loyalty_account}) → the ORDER residual
 * after it at or above what its target was paid, put on account or has in flight, else the whole transaction rolls
 * back. The headroom is checked on the adjusted bill, so every kind (a reward's price comes from loyalty) is measured
 * by orders' own amounts rather than a second computation of them.
 */
@Service
public class SettledDiscountService {

    private final AccessPolicy accessPolicy;
    private final ApproverVerifier approvers;
    private final SettlementLocks locks;
    private final Bills bills;
    private final SettlementViews views;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final SettlementAudit audit;

    SettledDiscountService(AccessPolicy accessPolicy, ApproverVerifier approvers, SettlementLocks locks, Bills bills,
                           SettlementViews views, OrdersApi orders, FiscalApi fiscal, SettlementAudit audit) {
        this.accessPolicy = accessPolicy;
        this.approvers = approvers;
        this.locks = locks;
        this.bills = bills;
        this.views = views;
        this.orders = orders;
        this.fiscal = fiscal;
        this.audit = audit;
    }

    /** Who vouched for a discount of {@code orderId}, checked before any transaction; {@code null}: nobody. */
    public record DiscountApproval(UUID orderId, UUID approverEmployeeId) {
    }

    /** The permission a {@code kind} needs (D106, D134), as on the orders route. */
    static Permission permissionFor(AdjustmentKind kind) {
        return switch (kind) {
            case DISCOUNT, REVERSAL -> Permission.ORDER_DISCOUNT;
            case OFFER -> Permission.ORDER_OFFER;
            case REWARD -> Permission.LOYALTY_REDEEM;
        };
    }

    /**
     * D40 phase 1: the kind's permission on the order's store (404, 403), then the approver, when the POS sent one,
     * verified there for {@code ORDER_DISCOUNT_APPROVE}. Whether one is needed is decided by orders, in phase 2.
     *
     * @throws ApprovalDeniedException 403 for half the headers or a refused approver
     */
    public DiscountApproval authorize(UUID orderId, AdjustmentKind kind, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(permissionFor(kind));
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        boolean noCode = approverCode == null || approverCode.isBlank();
        boolean noPin = approverPin == null || approverPin.isBlank();
        if (noCode && noPin) {
            return new DiscountApproval(orderId, null);
        }
        if (noCode || noPin) {
            throw new ApprovalDeniedException();
        }
        return new DiscountApproval(orderId, approvers.verify(approverCode, approverPin,
                Permission.ORDER_DISCOUNT_APPROVE, pos.storeId()));
    }

    /**
     * D40 phase 2: the headroom checks and the adjustment, in one transaction.
     *
     * @throws SettlementRuleException 422 {@code ORDER_NOT_OPEN}, {@code CORRECTION_REQUIRES_FISCAL_ADAPTER},
     *         {@code QUANTITY_PARTITIONED}, {@code DISCOUNT_EXCEEDS_UNPAID}; orders' 422s
     *         ({@code PRICE_INCREASE_NOT_ALLOWED}, {@code DISCOUNT_LIMIT_EXCEEDED}, {@code SELF_APPROVAL}, …) and its
     *         403 for a missing approver pass through
     */
    @Transactional
    public OrderSettlementView discount(UUID orderId, SettledDiscountRequest request, DiscountApproval approval) {
        if (!approval.orderId().equals(orderId)) {
            throw new IllegalArgumentException("The approval is for order " + approval.orderId());
        }
        PosContext pos = accessPolicy.requirePos(permissionFor(request.kind()));
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        locks.lockOrder(orderId);
        Bill bill = bills.of(orderId).requireOpen();
        if (bill.frozen()) {
            throw SettlementRuleException.correctionRequiresFiscalAdapter("Order " + orderId + " has its document");
        }
        Coverage coverage = fiscal.coverage(orderId);
        request.lines().stream().map(SettledDiscountRequest.Line::lineId).distinct().forEach(lineId -> {
            requireOnNoDocument(coverage, lineId);
            bill.split(lineId).ifPresent(SettledDiscountService::requireUnpartitioned);
        });
        OrderBillingView adjusted = orders.adjustLinePrice(command(orderId, request, bill, pos),
                approval.approverEmployeeId());
        requireHeadroom(bill, adjusted.due());
        audit.orderChanged(bill.storeId(), orderId);
        return views.of(orderId);
    }

    private static SettledAdjustmentCommand command(UUID orderId, SettledDiscountRequest request, Bill bill,
                                                    PosContext pos) {
        Money unitPrice = request.unitPrice() == null ? null : Money.of(request.unitPrice(), bill.due().currency());
        return new SettledAdjustmentCommand(orderId, request.orderVersion(), request.lines().stream()
                .map(line -> new AdjustLine(line.lineId(), line.quantity())).toList(), request.kind(),
                request.percent(), unitPrice, request.rewardCode(), request.reason(), pos.employeeId());
    }

    /** P31: a price on a document is corrected by a document. */
    private static void requireOnNoDocument(Coverage coverage, UUID lineId) {
        BigDecimal covered = SettlementViews.quantities(coverage.issued()).getOrDefault(lineId, BigDecimal.ZERO)
                .add(SettlementViews.quantities(coverage.draft()).getOrDefault(lineId, BigDecimal.ZERO));
        if (covered.signum() > 0) {
            throw SettlementRuleException.correctionRequiresFiscalAdapter("Line " + lineId + " is on a document");
        }
    }

    /** D105: a partition's share is fixed in amount, so no quantity of a discounted line may be in one. */
    private static void requireUnpartitioned(LineSplit split) {
        BigDecimal pool = split.pool().quantity();
        if (pool.compareTo(split.netQty()) < 0) {
            throw SettlementRuleException.discountedLinePartitioned(split.lineId(), pool);
        }
    }

    /** D71, D105: the ORDER residual after the discount (partitions are unchanged) stays at or above its owed. */
    private static void requireHeadroom(Bill bill, Money dueAfter) {
        Money residualAfter = bill.live().stream().map(p -> p.due()).reduce(dueAfter, Money::minus);
        Money owed = bill.orderOwed();
        if (residualAfter.amount().compareTo(owed.amount()) < 0) {
            throw SettlementRuleException.discountExceedsUnpaid(bill.view().orderId(), residualAfter, owed,
                    bill.residualDue().minus(owed));
        }
    }
}
