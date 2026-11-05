package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.CreditChargeStatus;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The steps of the D63 order commands ({@code /documents}, {@code /close}), each idempotent by domain state and each
 * in its own short transaction (or none), so a retry after a failure at any point resumes to the same end state.
 * Lock order inside a transaction (D51, D76): order_settlement → fiscal. R3 (D70): the order's own document is the
 * <em>residual</em> document, for what no partition document covers ({@link ResidualDocuments}).
 */
@Component
public class OrderSettlementSteps {

    private final AccessPolicy accessPolicy;
    private final SettlementLocks locks;
    private final Bills bills;
    private final SettlementViews views;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;

    OrderSettlementSteps(AccessPolicy accessPolicy, SettlementLocks locks, Bills bills, SettlementViews views,
                         OrdersApi orders, FiscalApi fiscal, PlatformTransactionManager transactionManager) {
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.bills = bills;
        this.views = views;
        this.orders = orders;
        this.fiscal = fiscal;
        this.inTransaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /**
     * s1, one transaction: the order's lock → {@code freezeForInvoice} → the residual ORDER draft (fiscal returns the
     * existing document, DRAFT or ISSUED). A close ({@code expectedVersion} given) first checks that the order is
     * settled (nothing in doubt on any target, paid = due), frozen or not, so a close that cannot succeed never
     * freezes the order, drafts or issues its document. Until the order is frozen it also checks the client's
     * version; once frozen nothing of the order can change, so a retry (whose client version predates the freeze)
     * skips it.
     *
     * @throws SettlementRuleException {@code NOTHING_TO_BILL} when there is no line, or every line is on partition
     *         documents already
     */
    public DocumentView prepareDocument(PosContext pos, UUID orderId, Buyer buyer, Long expectedVersion) {
        return inTransaction.execute(tx -> {
            Bill bill = lockedBill(pos, orderId);
            OrderBillingView view = bill.view();
            boolean closing = expectedVersion != null;
            if (closing) {
                requireSettled(bill); // frozen or not: a close that cannot succeed issues nothing
            }
            List<DocumentView> documents = fiscal.documentsOfOrder(orderId);
            DraftCommand residual = ResidualDocuments.keepingOnAccount(
                    ResidualDocuments.residual(bill, buyer, documents), documents);
            if (!bill.frozen()) {
                bill.requireOpen();
                if (closing) {
                    OptimisticLocking.requireVersion(OrderBillingView.class, orderId, view.version(), expectedVersion);
                }
                if (residual.lines().isEmpty()) {
                    throw new SettlementRuleException(SettlementRule.NOTHING_TO_BILL,
                            "Order " + orderId + " has nothing to bill: cancel it instead.");
                }
                orders.freezeForInvoice(orderId);
            }
            return fiscal.createDraft(residual);
        });
    }

    /** Whether a close needs the residual document: none is ISSUED and some quantity is on no other document. */
    public boolean needsResidualDocument(PosContext pos, UUID orderId) {
        return Boolean.TRUE.equals(readOnly.execute(tx -> {
            accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
            List<DocumentView> documents = fiscal.documentsOfOrder(orderId);
            Optional<DocumentView> own = documents.stream().filter(d -> "ORDER".equals(d.sourceType())).findFirst();
            if (own.isPresent()) {
                return !"ISSUED".equals(own.get().status());
            }
            return ResidualDocuments.hasUncovered(bills.of(orderId), documents);
        }));
    }

    /** s2, no transaction (D55, D63): issues the draft; an ISSUED document comes back unchanged. */
    public DocumentView issue(PosContext pos, UUID documentId) {
        return fiscal.issue(documentId, pos.employeeId());
    }

    /**
     * s3, one transaction (D50 §7.8, D70 step 3): the order's lock; an order already CLOSED answers with its view
     * (idempotent by state); otherwise nothing in doubt on any target, paid = due, every document of the order
     * ISSUED and together covering every line's net quantity and the due, then {@code OrdersApi.close} at the version
     * read under the lock (the freeze in s1 moved it), which also frees the table.
     */
    public OrderSettlementView close(PosContext pos, UUID orderId) {
        return inTransaction.execute(tx -> {
            Bill bill = lockedBill(pos, orderId);
            if (bill.view().lifecycle() == OrderLifecycle.CLOSED) {
                return views.of(bill);
            }
            bill.requireOpen();
            requireSettled(bill);
            requireInvoiced(bill);
            orders.close(orderId, bill.view().version(), pos.employeeId());
            return views.of(orderId);
        });
    }

    /** D70 (3): every document ISSUED, Σ covered quantity = net quantity per line, Σ document gross = due. */
    private void requireInvoiced(Bill bill) {
        UUID orderId = bill.view().orderId();
        List<DocumentView> documents = fiscal.documentsOfOrder(orderId);
        Optional<DocumentView> draft = documents.stream().filter(d -> !"ISSUED".equals(d.status())).findFirst();
        if (draft.isPresent()) {
            throw fiscalPending(orderId, draft.get().id());
        }
        Coverage coverage = fiscal.coverage(orderId);
        Map<UUID, BigDecimal> issued = SettlementViews.quantities(coverage.issued());
        Money invoiced = coverage.issued().stream().map(Coverage.CoveredLine::gross)
                .reduce(Money.of(BigDecimal.ZERO, bill.due().currency()), Money::plus);
        boolean covered = bill.view().lines().stream().allMatch(line ->
                issued.getOrDefault(line.lineId(), BigDecimal.ZERO).compareTo(line.netQty()) == 0);
        if (!covered || invoiced.amount().compareTo(bill.due().amount()) != 0
                || issued.keySet().stream().anyMatch(id -> bill.line(id).isEmpty())) {
            throw fiscalPending(orderId, null);
        }
    }

    /** D42 (b) then D51: the order's store only (404, 403), then its settlement lock, then the bill as committed. */
    private Bill lockedBill(PosContext pos, UUID orderId) {
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        locks.lockOrder(orderId);
        return bills.of(orderId);
    }

    private void requireSettled(Bill bill) {
        UUID orderId = bill.view().orderId();
        bill.openAttempts().stream().findFirst().ifPresent(open -> {
            throw SettlementConflictException.inDoubt("order", orderId, open.getId());
        });
        bill.liveCharges().stream().filter(c -> c.getStatus() == CreditChargeStatus.RESERVED).findFirst()
                .ifPresent(reserved -> {
                    throw new SettlementRuleException(SettlementRule.NOT_SETTLED, "Order " + orderId + " is not "
                            + "settled: its credit charge " + reserved.getId() + " is not posted yet.",
                            Map.of("balance", bill.balance().amount(), "chargeId", reserved.getId()));
                });
        if (bill.balance().amount().signum() != 0) {
            throw new SettlementRuleException(SettlementRule.NOT_SETTLED, "Order " + orderId + " is not settled: "
                    + bill.paid().plus(bill.credit()).amount() + " paid of " + bill.due().amount() + " due.",
                    Map.of("balance", bill.balance().amount()));
        }
    }

    private static SettlementRuleException fiscalPending(UUID orderId, UUID documentId) {
        String detail = "The sale document of order " + orderId + " is not issued yet.";
        return documentId == null ? new SettlementRuleException(SettlementRule.FISCAL_PENDING, detail)
                : new SettlementRuleException(SettlementRule.FISCAL_PENDING, detail, Map.of("documentId", documentId));
    }
}
