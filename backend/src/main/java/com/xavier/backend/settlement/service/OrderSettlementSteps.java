package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The steps of the D63 order commands ({@code /documents}, {@code /close}), each idempotent by domain state and each
 * in its own short transaction (or none), so a retry after a failure at any point resumes to the same end state.
 * Lock order inside a transaction (D51): order_settlement → fiscal.
 */
@Component
public class OrderSettlementSteps {

    private final AccessPolicy accessPolicy;
    private final SettlementLocks locks;
    private final Bills bills;
    private final SettlementViews views;
    private final PaymentAttemptRepository attempts;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final TransactionTemplate inTransaction;

    OrderSettlementSteps(AccessPolicy accessPolicy, SettlementLocks locks, Bills bills, SettlementViews views,
                         PaymentAttemptRepository attempts, OrdersApi orders, FiscalApi fiscal,
                         PlatformTransactionManager transactionManager) {
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.bills = bills;
        this.views = views;
        this.attempts = attempts;
        this.orders = orders;
        this.fiscal = fiscal;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * s1, one transaction: the order's lock → {@code freezeForInvoice} → the SALE draft from the frozen bill (fiscal
     * returns the existing document, DRAFT or ISSUED). Until the order is frozen, a close ({@code expectedVersion}
     * given) also checks the client's version and that the order is settled, so a close that cannot succeed never
     * freezes the order or drafts its document. Once frozen nothing of the order can change, so a retry (whose
     * client version predates the freeze) skips those checks.
     */
    public DocumentView prepareDocument(PosContext pos, UUID orderId, Buyer buyer, Long expectedVersion) {
        return inTransaction.execute(tx -> {
            Bill bill = lockedBill(pos, orderId);
            OrderBillingView view = bill.view();
            if (view.billingLock() != BillingLock.INVOICE) {
                bill.requireOpen();
                if (expectedVersion != null) {
                    OptimisticLocking.requireVersion(OrderBillingView.class, orderId, view.version(), expectedVersion);
                    requireSettled(bill);
                }
                if (view.lines().isEmpty()) {
                    throw new SettlementRuleException(SettlementRule.NOTHING_TO_BILL,
                            "Order " + orderId + " has nothing to bill: cancel it instead.");
                }
                orders.freezeForInvoice(orderId);
            }
            return fiscal.createSaleDraft(draftOf(view, buyer));
        });
    }

    /** s2, no transaction (D55, D63): issues the draft; an ISSUED document comes back unchanged. */
    public DocumentView issue(PosContext pos, UUID documentId) {
        return fiscal.issue(documentId, pos.employeeId());
    }

    /**
     * s3, one transaction (D50 §7.8): the order's lock; an order already CLOSED answers with its view (idempotent by
     * state); otherwise nothing in doubt, paid = due and an ISSUED document, then {@code OrdersApi.close} at the
     * version read under the lock (the freeze in s1 moved it), which also frees the table.
     */
    public OrderSettlementView close(PosContext pos, UUID orderId) {
        return inTransaction.execute(tx -> {
            Bill bill = lockedBill(pos, orderId);
            if (bill.view().lifecycle() == OrderLifecycle.CLOSED) {
                return views.of(bill);
            }
            bill.requireOpen();
            requireSettled(bill);
            DocumentView document = fiscal.saleDocumentFor(orderId)
                    .filter(found -> "ISSUED".equals(found.status()))
                    .orElseThrow(() -> fiscalPending(orderId));
            orders.close(orderId, bill.view().version(), pos.employeeId());
            return views.of(orderId);
        });
    }

    private Bill lockedBill(PosContext pos, UUID orderId) {
        locks.lockOrder(orderId);
        Bill bill = bills.of(orderId);
        accessPolicy.requireSessionStore(pos, bill.storeId(), PaymentAttemptService.ORDER, orderId);
        return bill;
    }

    private void requireSettled(Bill bill) {
        UUID orderId = bill.view().orderId();
        attempts.findByOrderIdAndStatusInOrderByCreatedAt(orderId, AttemptStatus.OPEN).stream().findFirst()
                .ifPresent(open -> {
                    throw SettlementConflictException.inDoubt("order", orderId, open.getId());
                });
        if (bill.balance().amount().signum() != 0) {
            throw new SettlementRuleException(SettlementRule.NOT_SETTLED, "Order " + orderId + " is not settled: "
                    + bill.paid().amount() + " paid of " + bill.due().amount() + " due.",
                    Map.of("balance", bill.balance().amount()));
        }
    }

    private SettlementRuleException fiscalPending(UUID orderId) {
        UUID documentId = fiscal.saleDocumentFor(orderId).map(DocumentView::id).orElse(null);
        String detail = "The sale document of order " + orderId + " is not issued yet.";
        return documentId == null ? new SettlementRuleException(SettlementRule.FISCAL_PENDING, detail)
                : new SettlementRuleException(SettlementRule.FISCAL_PENDING, detail, Map.of("documentId", documentId));
    }

    /** The billing lines exactly as orders computed them (D59): fiscal re-checks the same formula per line. */
    static SaleDraftCommand draftOf(OrderBillingView view, Buyer buyer) {
        List<DraftLine> lines = view.lines().stream().map(line -> new DraftLine(line.lineId(), line.description(),
                line.netQty(), line.unitPrice(), line.ratePercent(), line.gross(), line.net(), line.tax())).toList();
        Money zero = Money.of(java.math.BigDecimal.ZERO, view.due().currency());
        return new SaleDraftCommand(view.storeId(), view.orderId(), buyer, lines, view.due(),
                sum(view.lines(), BillingLine::net, zero), sum(view.lines(), BillingLine::tax, zero));
    }

    private static Money sum(List<BillingLine> lines, Function<BillingLine, Money> amount, Money zero) {
        return lines.stream().map(amount).reduce(zero, Money::plus);
    }
}
