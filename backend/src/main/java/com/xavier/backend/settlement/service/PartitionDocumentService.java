package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.BillPartitionLine;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.dto.IssueDocumentRequest;
import com.xavier.backend.settlement.repository.PartitionRef;
import com.xavier.backend.settlement.service.Bills.Bill;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An ITEMS partition invoiced on its own (D70), a D63 multi-step command with no transaction around it: s1, one
 * transaction, takes {@code order_settlement} and creates the PARTITION draft from the partition's own shares (D68,
 * {@code amountsAllocated}); s2 issues it with no transaction. It does not freeze the order: the table keeps ordering
 * (§8.2), and new consumption lands in the residual. A retry with the same key resumes: an existing draft is drafted
 * again with the same content (only the buyer may change) and an ISSUED document comes back unchanged.
 */
@Service
public class PartitionDocumentService {

    private final AccessPolicy accessPolicy;
    private final PartitionService partitions;
    private final SettlementLocks locks;
    private final Bills bills;
    private final FiscalApi fiscal;
    private final SettlementAudit audit;
    private final TransactionTemplate inTransaction;

    PartitionDocumentService(AccessPolicy accessPolicy, PartitionService partitions, SettlementLocks locks,
                             Bills bills, FiscalApi fiscal, SettlementAudit audit,
                             PlatformTransactionManager transactionManager) {
        this.accessPolicy = accessPolicy;
        this.partitions = partitions;
        this.locks = locks;
        this.bills = bills;
        this.fiscal = fiscal;
        this.audit = audit;
        this.inTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * @throws SettlementRuleException 422 {@code PARTITION_NOT_INVOICEABLE}, {@code ORDER_NOT_OPEN},
     *         {@code ORDER_FROZEN}
     * @throws com.xavier.backend.fiscal.FiscalIssueFailedException 422 {@code FISCAL_PENDING} {documentId}
     */
    public DocumentView issue(UUID partitionId, IssueDocumentRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.PAYMENT_COLLECT);
        DocumentView draft = inTransaction.execute(tx -> draft(pos, partitionId, request));
        return fiscal.issue(draft.id(), pos.employeeId());
    }

    private DocumentView draft(PosContext pos, UUID partitionId, IssueDocumentRequest request) {
        PartitionRef ref = partitions.authorized(pos, partitionId);
        locks.lockOrder(ref.orderId());
        Bill bill = bills.of(ref.orderId());
        BillPartition partition = bill.partition(partitionId).orElseThrow();
        if (partition.getKind() != PartitionKind.ITEMS || !partition.isLive()) {
            throw SettlementRuleException.partitionNotInvoiceable(partitionId, partition.getStatus(),
                    partition.getKind());
        }
        List<DocumentView> documents = fiscal.documentsOfOrder(ref.orderId());
        boolean drafted = documents.stream()
                .anyMatch(d -> "PARTITION".equals(d.sourceType()) && partitionId.equals(d.sourceId()));
        if (!drafted) {
            // once the residual is drafted (INVOICE), an undrafted partition's lines are on it
            bill.requireOpen().requireNotFrozen();
        }
        DocumentView document = fiscal.createDraft(ResidualDocuments.keepingOnAccount(
                draftOf(bill, partition, request.buyer()), documents));
        if (!drafted) {
            audit.record("BILL_PARTITION_DRAFTED", PartitionService.PARTITION, partitionId, bill.storeId(),
                    Map.of("orderId", ref.orderId().toString(), "documentId", document.id().toString()));
            audit.orderChanged(bill.storeId(), ref.orderId());
        }
        return document;
    }

    static DraftCommand draftOf(Bill bill, BillPartition partition,
                                com.xavier.backend.fiscal.model.dto.Buyer buyer) {
        List<DraftLine> lines = partition.getLines().stream().map(line -> draftLine(bill, line)).toList();
        return ResidualDocuments.command(bill, SourceType.PARTITION, partition.getId(), buyer, lines);
    }

    private static DraftLine draftLine(Bill bill, BillPartitionLine line) {
        BillingLine billing = bill.line(line.getOrderLineId()).orElseThrow();
        return new DraftLine(billing.lineId(), billing.description(), line.getQuantity(), billing.unitPrice(),
                billing.ratePercent(), line.gross(), line.net(), line.tax());
    }
}
