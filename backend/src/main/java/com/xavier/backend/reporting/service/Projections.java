package com.xavier.backend.reporting.service;

import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.dining.model.dto.TableInfo;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.VoidFacts;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.reporting.repository.ProjectionStore;
import com.xavier.backend.reporting.repository.ProjectionStore.CreditRecord;
import com.xavier.backend.reporting.repository.ProjectionStore.MoneyRecord;
import com.xavier.backend.reporting.repository.ProjectionStore.SalesHeader;
import com.xavier.backend.reporting.repository.ProjectionStore.SalesLine;
import com.xavier.backend.reporting.repository.ProjectionStore.SalesTax;
import com.xavier.backend.reporting.repository.ProjectionStore.VoidRecord;
import com.xavier.backend.settlement.PaymentFact;
import com.xavier.backend.settlement.RefundFact;
import com.xavier.backend.settlement.SettlementFacts;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects one immutable fact (D84): pulls it through the owner's read-only facade, resolves its dimensions once
 * (D85: business date, and the store's wall clock at the fact's instant) and inserts its rows keyed by the source id.
 * A fact already projected is left as it is. The live listeners and the rebuild both come through here.
 */
@Service
public class Projections {

    private final FiscalApi fiscal;
    private final OrdersApi orders;
    private final DiningApi dining;
    private final OrganizationApi organization;
    private final SettlementFacts settlement;
    private final ReleaseFamilies families;
    private final ProjectionStore store;
    private final Clock clock;

    Projections(FiscalApi fiscal, OrdersApi orders, DiningApi dining, OrganizationApi organization,
                SettlementFacts settlement, ReleaseFamilies families, ProjectionStore store, Clock clock) {
        this.settlement = settlement;
        this.fiscal = fiscal;
        this.orders = orders;
        this.dining = dining;
        this.organization = organization;
        this.families = families;
        this.store = store;
        this.clock = clock;
    }

    /**
     * An ISSUED sale document as a sales record with its lines and its tax summary per rate. The amounts and the tax
     * are the document's (never recomputed from the order); the line dimensions come from the order's billing view.
     * A document that is not ISSUED is not a sale yet and is ignored.
     */
    @Transactional
    public void projectDocument(UUID documentId) {
        DocumentFacts doc = fiscal.documentFacts(documentId);
        if (!"ISSUED".equals(doc.status())) {
            return;
        }
        LocalDateTime local = LocalDateTime.ofInstant(doc.issuedAt(), zoneOf(doc.storeId()));
        SalesHeader header = new SalesHeader(doc.documentId(), doc.storeId(), doc.businessDate(),
                local.toLocalDate(), local.getHour(), doc.issuedAt(), doc.orderId(),
                "PARTITION".equals(doc.sourceType()) ? doc.sourceId() : null, doc.terminalId(),
                doc.issuedByEmployeeId(), doc.customerId(), doc.onAccount(), doc.seriesCode(), doc.number(),
                Boolean.TRUE.equals(doc.nonFiscal()), doc.totalGross().currency().getCurrencyCode(),
                doc.totalGross().amount(), doc.totalNet().amount(), doc.totalTax().amount());
        Instant now = clock.instant();
        if (!store.insertSalesHeader(header, now)) {
            return;
        }
        Dimensions dimensions = dimensionsOf(doc.orderId());
        for (DocumentView.Line line : doc.lines()) {
            store.insertSalesLine(header, salesLine(doc.storeId(), line, dimensions), now);
        }
        for (DocumentView.TaxSummary tax : doc.taxes()) {
            String taxCode = tax.taxCode() != null ? tax.taxCode() : taxCodes(doc.lines(), tax.rate(), dimensions);
            store.insertSalesTax(header, new SalesTax(taxCode, tax.rate(), tax.net().amount(), tax.tax().amount()),
                    now);
        }
    }

    /** A rebuild's first step: the store's projection rows in {@code [from, to]} (projections, never facts). */
    @Transactional
    public void clear(UUID storeId, LocalDate from, LocalDate to) {
        store.deleteRange(storeId, from, to);
    }

    /** A void of a confirmed line, in its order's business day; local hour at the void (D85). */
    @Transactional
    public void projectVoid(UUID voidId) {
        VoidFacts v = orders.voidFacts(voidId);
        store.insertVoid(new VoidRecord(v.voidId(), v.storeId(), v.businessDay(), localHour(v.storeId(), v.at()),
                v.lineSnapshot().itemCode(), v.lineSnapshot().description(), v.quantity(), v.gross().amount(),
                v.reason(), v.wasted(), v.afterDispatch(), v.operatorEmployeeId(), v.approverEmployeeId(),
                v.lineSnapshot().lineId(), v.at()),
                clock.instant());
    }

    /** A confirmed payment, in its session's cash day (D85). An unknown payment has no fact yet and is ignored. */
    @Transactional
    public void projectPayment(UUID paymentId) {
        settlement.paymentFact(paymentId).ifPresent(this::insert);
    }

    /** A refund, signed negative, in the refunder's cash day (D85). Empty until S30 records refunds. */
    @Transactional
    public void projectRefund(UUID refundId) {
        settlement.refundFact(refundId).ifPresent(this::insert);
    }

    /** A posted credit charge, apart from money (D87), in its order's business day. Empty until S29 posts them. */
    @Transactional
    public void projectCreditCharge(UUID chargeId) {
        settlement.creditChargeFact(chargeId).ifPresent(charge -> store.insertCredit(new CreditRecord(
                charge.chargeId(), charge.storeId(), charge.businessDate(), charge.customerId(), charge.orderId(),
                charge.amount().amount()), clock.instant()));
    }

    private void insert(PaymentFact p) {
        store.insertMoney(new MoneyRecord("PAYMENT", p.paymentId(), p.storeId(), p.businessDate(),
                localHour(p.storeId(), p.at()), p.methodCode(), p.amount().amount(), p.targetType(), p.orderId(),
                p.customerId(), p.collectedByEmployeeId(), p.terminalId()), clock.instant());
    }

    private void insert(RefundFact r) {
        String target = r.partitionId() != null ? "PARTITION" : r.orderId() != null ? "ORDER" : "CREDIT_ACCOUNT";
        store.insertMoney(new MoneyRecord("REFUND", r.refundId(), r.storeId(), r.businessDate(),
                localHour(r.storeId(), r.at()), r.methodCode(), r.amount().amount().negate(), target, r.orderId(),
                r.customerId(), r.refundedByEmployeeId(), r.terminalId()), clock.instant());
    }

    private int localHour(UUID storeId, Instant at) {
        return LocalDateTime.ofInstant(at, zoneOf(storeId)).getHour();
    }

    private SalesLine salesLine(UUID storeId, DocumentView.Line line, Dimensions dimensions) {
        BillingLine billed = dimensions.lines().get(line.sourceLineId());
        UUID itemId = billed == null ? null : billed.itemId();
        return new SalesLine(line.lineNo(), itemId, billed == null ? null : billed.itemCode(),
                billed == null ? null : families.familyOf(storeId, billed.releaseId(), itemId), line.description(),
                line.quantity(), line.gross().amount(), line.net().amount(), line.tax().amount(),
                billed == null ? null : billed.taxCode(), line.taxRatePercent(),
                billed == null ? null : billed.servedByEmployeeId(),
                dimensions.table() == null ? null : dimensions.table().areaId(),
                dimensions.table() == null ? null : dimensions.table().areaCode(), dimensions.mode());
    }

    /**
     * The tax code(s) at {@code rate} of a document drafted before its tax summary named them: derived from the
     * order's billed lines (joined with "+" when two codes share a rate, as fiscal does), and empty when the order's
     * lines no longer say. A newer document's {@code TaxSummary.taxCode} is used as it is; the amounts always come
     * from the summary.
     */
    private static String taxCodes(List<DocumentView.Line> lines, BigDecimal rate, Dimensions dimensions) {
        return lines.stream().filter(line -> line.taxRatePercent().compareTo(rate) == 0)
                .map(line -> dimensions.lines().get(line.sourceLineId())).filter(Objects::nonNull)
                .map(BillingLine::taxCode).filter(Objects::nonNull).distinct().sorted()
                .collect(Collectors.joining("+"));
    }

    /**
     * The order's billing lines by id, its table's area (the table's current area: a documented approximation, D84)
     * and its mode (DINE_IN, COUNTER or TAKEAWAY). The serving employee is the line's {@code servedByEmployeeId}: the
     * round's confirmer, and for a line transferred in, the employee who made the transfer (who took the line over on
     * this order); reporting cannot see the source order's round, and a transfer is a confirmed fact of its own.
     */
    private Dimensions dimensionsOf(UUID orderId) {
        if (orderId == null) {
            return new Dimensions(Map.of(), null, null);
        }
        OrderBillingView order = orders.billingView(orderId);
        TableInfo table = order.tableId() == null ? null : dining.tableInfo(order.tableId()).orElse(null);
        return new Dimensions(order.lines().stream()
                .collect(Collectors.toMap(BillingLine::lineId, Function.identity())), table, order.mode().name());
    }

    /** The store's zone now: a fact's local date and hour are computed once, when it is projected (D85). */
    private ZoneId zoneOf(UUID storeId) {
        return organization.zoneOf(storeId);
    }

    private record Dimensions(Map<UUID, BillingLine> lines, TableInfo table, String mode) {
    }
}
