package com.xavier.backend.settlement.service;

import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.settlement.service.Bills.Bill;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one settlement caller of {@link FiscalApi#issue} (D97 rule 4, an ArchUnit rule): first one short transaction,
 * {@code order_settlement FOR UPDATE} → {@link DocumentPayments#of} → {@code FiscalApi.recordPayments} on the DRAFT,
 * then the issue with no transaction (D50 §7.6, §7.3). So a document states the position at the refresh, milliseconds
 * before its number: a payment confirmed in between is not on it (nothing is locked across the issue), and the live
 * settlement view shows it. An ISSUED document is not refreshed, and comes back unchanged.
 */
@Component
public class SettlementIssuer {

    private final SettlementLocks locks;
    private final Bills bills;
    private final DocumentPayments payments;
    private final FiscalApi fiscal;
    private final TransactionTemplate inTransaction;

    SettlementIssuer(SettlementLocks locks, Bills bills, DocumentPayments payments, FiscalApi fiscal,
                     PlatformTransactionManager transactionManager) {
        this.locks = locks;
        this.bills = bills;
        this.payments = payments;
        this.fiscal = fiscal;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * The caller authorized the command on the order (D42). Call with no transaction.
     *
     * @throws com.xavier.backend.fiscal.FiscalIssueFailedException 422 {@code FISCAL_PENDING} {documentId}: the draft
     *         stays pending (with its snapshot), and a retry refreshes and issues it with no gap
     * @throws com.xavier.backend.settlement.exception.SettlementNotFoundException 404 when the document is not one
     *         of the order's
     */
    public DocumentView issue(PosContext pos, UUID orderId, UUID documentId) {
        inTransaction.executeWithoutResult(tx -> refresh(orderId, documentId));
        return fiscal.issue(documentId, pos.employeeId());
    }

    private void refresh(UUID orderId, UUID documentId) {
        locks.lockOrder(orderId);
        Bill bill = bills.of(orderId);
        DocumentView document = fiscal.documentsOfOrder(orderId).stream().filter(d -> d.id().equals(documentId))
                .findFirst().orElseThrow(() -> new com.xavier.backend.settlement.exception
                        .SettlementNotFoundException("document", documentId));
        if (!"ISSUED".equals(document.status())) {
            fiscal.recordPayments(documentId, payments.of(bill, document));
        }
    }
}
