package com.xavier.backend.settlement.service;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.FinancialState;
import com.xavier.backend.settlement.model.FiscalState;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.dto.LineCoverage;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.model.dto.PartitionView;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Builds an {@link OrderSettlementView} and the {@link PartitionView}s of an order from its bill and documents. */
@Component
class SettlementViews {

    private final Bills bills;
    private final FiscalApi fiscal;
    private final TaxRoundingAdjustments taxRounding;

    SettlementViews(Bills bills, FiscalApi fiscal, TaxRoundingAdjustments taxRounding) {
        this.bills = bills;
        this.fiscal = fiscal;
        this.taxRounding = taxRounding;
    }

    OrderSettlementView of(UUID orderId) {
        return of(bills.of(orderId));
    }

    OrderSettlementView of(Bill bill) {
        UUID orderId = bill.view().orderId();
        List<DocumentView> documents = fiscal.documentsOfOrder(orderId);
        Optional<DocumentView> document = documents.stream().filter(d -> "ORDER".equals(d.sourceType()))
                .findFirst();
        return new OrderSettlementView(orderId, bill.storeId(), bill.view().lifecycle().name(),
                bill.view().version(), bill.due(), bill.paid(), bill.balance(), bill.financialState().name(),
                document.map(SettlementViews::fiscalState).orElse(FiscalState.NONE).name(),
                document.map(DocumentView::id).orElse(null),
                bill.openAttempts().stream().map(PaymentAttempt::toView).toList(),
                partitions(bill, documents), coverage(bill, fiscal.coverage(orderId)), bill.residualBalance(),
                taxRounding.differences(bill), bill.credit(),
                bill.charges().stream().map(com.xavier.backend.settlement.model.CreditCharge::toView).toList(),
                bill.refunds().stream().map(com.xavier.backend.settlement.model.Refund::toView).toList(),
                taxRounding.views(orderId),
                PaymentStatus.of(bill.due(), bill.paid(), bill.credit()).name());
    }

    List<PartitionView> partitions(Bill bill) {
        return partitions(bill, fiscal.documentsOfOrder(bill.view().orderId()));
    }

    PartitionView partition(Bill bill, BillPartition partition) {
        return of(bill, partition, partitionDocuments(fiscal.documentsOfOrder(bill.view().orderId())));
    }

    private List<PartitionView> partitions(Bill bill, List<DocumentView> documents) {
        Map<UUID, DocumentView> byPartition = partitionDocuments(documents);
        return bill.partitions().stream().map(p -> of(bill, p, byPartition)).toList();
    }

    static Map<UUID, DocumentView> partitionDocuments(List<DocumentView> documents) {
        return documents.stream().filter(d -> "PARTITION".equals(d.sourceType()))
                .collect(Collectors.toMap(DocumentView::sourceId, Function.identity()));
    }

    private static PartitionView of(Bill bill, BillPartition partition, Map<UUID, DocumentView> documents) {
        Money paid = bill.paidOf(partition);
        Money credit = bill.creditOn(partition.getId());
        Money balance = bill.balanceOf(partition);
        DocumentView document = documents.get(partition.getId());
        List<PartitionView.Line> lines = partition.getLines().stream().map(line -> new PartitionView.Line(
                line.getOrderLineId(), bill.line(line.getOrderLineId()).map(BillingLine::description).orElse(null),
                line.getQuantity(), line.gross(), line.net(), line.tax())).toList();
        return new PartitionView(partition.getId(), partition.getOrderId(), partition.getKind().name(),
                partition.getStatus().name(), partition.getLabel(), partition.getGroupId(), partition.getPartNo(),
                partition.getParts(), lines, partition.due(), paid, credit, balance,
                FinancialState.of(partition.due(), paid.plus(credit)).name(),
                document != null && "ISSUED".equals(document.status()), document == null ? null : document.id(),
                partition.currentVersion(), partition.getCreatedAt(),
                PaymentStatus.of(partition.due(), paid, credit).name());
    }

    private static List<LineCoverage> coverage(Bill bill, Coverage coverage) {
        Map<UUID, BigDecimal> issued = quantities(coverage.issued());
        Map<UUID, BigDecimal> drafted = quantities(coverage.draft());
        return bill.view().lines().stream().map(line -> {
            LineSplit split = bill.split(line);
            BigDecimal partitioned = split.netQty().subtract(split.pool().quantity());
            return new LineCoverage(line.lineId(), line.description(), line.netQty(), line.gross(), partitioned,
                    issued.getOrDefault(line.lineId(), BigDecimal.ZERO),
                    drafted.getOrDefault(line.lineId(), BigDecimal.ZERO));
        }).toList();
    }

    static Map<UUID, BigDecimal> quantities(List<Coverage.CoveredLine> lines) {
        return lines.stream().collect(Collectors.toMap(Coverage.CoveredLine::sourceLineId,
                Coverage.CoveredLine::quantity, BigDecimal::add));
    }

    static FiscalState fiscalState(DocumentView document) {
        return "ISSUED".equals(document.status()) ? FiscalState.ISSUED : FiscalState.DRAFT;
    }
}
