package com.xavier.backend.settlement.service;

import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The order's own SALE draft (D70): the residual document. It covers exactly what no partition document covers,
 * per billing line: one line per LIVE ITEMS share whose partition has no document, with that share's amounts, and one
 * line for the line's pool (what no LIVE partition holds). Each document line is then one share or one pool, so each
 * stays within fiscal's bound (D68 amendment); together with the partition documents they sum to the order's lines.
 * An unpartitioned order's residual is its billing lines exactly (R2's document, strict D59).
 */
final class ResidualDocuments {

    private ResidualDocuments() {
    }

    /** @param documents the order's documents (DRAFT or ISSUED), for which partitions are already invoiced */
    static DraftCommand residual(Bill bill, Buyer buyer, List<DocumentView> documents) {
        Set<UUID> invoiced = documents.stream().filter(d -> "PARTITION".equals(d.sourceType()))
                .map(DocumentView::sourceId).collect(Collectors.toSet());
        List<DraftLine> lines = new ArrayList<>();
        for (BillingLine line : bill.view().lines()) {
            LineSplit split = bill.split(line);
            for (LineSplit.Piece piece : split.live()) {
                if (!invoiced.contains(piece.partitionId())) {
                    lines.add(draftLine(line, piece.quantity(), piece.gross(), piece.tax()));
                }
            }
            LineSplit.Pool pool = split.pool();
            if (pool.quantity().signum() > 0) {
                lines.add(draftLine(line, pool.quantity(), pool.gross(), pool.tax()));
            }
        }
        return command(bill, SourceType.ORDER, bill.view().orderId(), buyer, lines);
    }

    /** Whether the order has billable quantity no document covers yet (D70 step 2). */
    static boolean hasUncovered(Bill bill, List<DocumentView> documents) {
        return !residual(bill, null, documents.stream().filter(d -> !"ORDER".equals(d.sourceType())).toList())
                .lines().isEmpty();
    }

    static DraftCommand command(Bill bill, SourceType source, UUID sourceId, Buyer buyer, List<DraftLine> lines) {
        Money zero = Money.of(BigDecimal.ZERO, bill.due().currency());
        return new DraftCommand(bill.storeId(), source, sourceId, bill.view().orderId(), bill.view().businessDay(),
                buyer, null, false, lines, sum(lines, DraftLine::gross, zero), sum(lines, DraftLine::net, zero),
                sum(lines, DraftLine::tax, zero), source == SourceType.PARTITION || bill.everPartitioned());
    }

    /**
     * Fiscal keeps a draft on account once a credit sale marked it (D74): while that charge is live, a later draft of
     * the same document (a /documents call, a retried partition document) repeats the mark. A cancelled charge clears
     * it ({@code FiscalApi.clearOnAccount}), so nothing is repeated after.
     */
    static DraftCommand keepingOnAccount(DraftCommand draft, List<DocumentView> documents) {
        return documents.stream()
                .filter(d -> d.onAccount() && d.sourceType().equals(draft.sourceType().name())
                        && d.sourceId().equals(draft.sourceId()))
                .findFirst().map(d -> onAccount(draft, draft.buyer(), d.customerId())).orElse(draft);
    }

    /** The same draft as a credit sale's (D74): the customer's snapshot as buyer, on account for that customer. */
    static DraftCommand onAccount(DraftCommand draft, Buyer buyer, UUID customerId) {
        return new DraftCommand(draft.storeId(), draft.sourceType(), draft.sourceId(), draft.orderId(),
                draft.businessDate(), buyer, customerId, true, draft.lines(), draft.totalGross(), draft.totalNet(),
                draft.totalTax(), draft.amountsAllocated());
    }

    private static DraftLine draftLine(BillingLine line, BigDecimal quantity, Money gross, Money tax) {
        return new DraftLine(line.lineId(), line.description(), quantity, line.unitPrice(), line.ratePercent(),
                gross, gross.minus(tax), tax);
    }

    private static Money sum(List<DraftLine> lines, Function<DraftLine, Money> amount, Money zero) {
        return lines.stream().map(amount).reduce(zero, Money::plus);
    }
}
