package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Charge;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Correction;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Document;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Line;
import com.xavier.backend.settlement.reconciliation.model.dto.DocumentException;
import com.xavier.backend.settlement.reconciliation.model.dto.ExplainedRoundingDifference;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D80: documents against payments, computed from one order's facts (a pure function; nothing is stored, so nothing
 * goes stale). Exceptions:
 * <ul>
 *   <li>{@code DOCUMENT_UNPAID}: a closed order's ISSUED documents total more than it was paid (net of refunds) and
 *       put on account by POSTED charges (D70 forbids it: a bug alarm).</li>
 *   <li>{@code ON_ACCOUNT_WITHOUT_CHARGE}: an ISSUED on-account document that no POSTED charge names.</li>
 *   <li>{@code CHARGE_DEBIT_MISSING}: a POSTED charge whose customer ledger debit is missing.</li>
 *   <li>{@code COVERAGE_MISMATCH}: a closed order's line whose ISSUED quantity is not its net quantity.</li>
 *   <li>{@code CHARGE_RESERVED_STALE}: a charge RESERVED for more than {@link #STALE}.</li>
 *   <li>{@code DRAFT_STALE}: a DRAFT of a closed order, or whose issue failed more than {@link #STALE} ago.</li>
 *   <li>{@code TAX_DRIFT_UNASSIGNED}: per rate of a closed order, a settled void's tax cent that no adjustment records
 *       (D96: the residual draft or the close records each one; a bug alarm).</li>
 *   <li>{@code TAX_CORRECTION_PENDING}: one per open pending fiscal correction of a rate whose difference the
 *       recorded corrections explain (D96 rule 8).</li>
 *   <li>{@code TAX_MISMATCH}: per rate of a closed order, the lines' tax minus the ISSUED documents' tax is not what its
 *       recorded pending corrections (open and resolved) hold.</li>
 * </ul>
 * D96: the order's tax per rate is the target, and a difference is explained only by a recorded pending correction,
 * never by the split alone. A resolved one is reported as an explained rounding difference with its reference.
 */
public final class DocumentsControl {

    public static final Duration STALE = Duration.ofHours(1);
    static final String CLOSED = "CLOSED";
    static final String ISSUED = "ISSUED";
    static final String DRAFT = "DRAFT";
    static final String POSTED = "POSTED";
    static final String RESERVED = "RESERVED";

    private DocumentsControl() {
    }

    public record Findings(List<DocumentException> exceptions, List<ExplainedRoundingDifference> roundingDifferences) {
    }

    public static Findings check(OrderFacts order, Instant now) {
        List<DocumentException> exceptions = new ArrayList<>();
        List<ExplainedRoundingDifference> rounding = new ArrayList<>();
        boolean closed = CLOSED.equals(order.lifecycle());
        Instant staleBefore = now.minus(STALE);
        List<Document> issued = order.documents().stream().filter(d -> ISSUED.equals(d.status())).toList();
        if (closed) {
            unpaid(order, issued).ifPresent(exceptions::add);
            exceptions.addAll(coverage(order));
            tax(order, issued, exceptions, rounding);
        }
        Set<UUID> chargedDocuments = order.charges().stream().filter(c -> POSTED.equals(c.status()))
                .map(Charge::documentId).collect(Collectors.toSet());
        for (Document document : order.documents()) {
            if (ISSUED.equals(document.status()) && document.onAccount()
                    && !chargedDocuments.contains(document.documentId())) {
                exceptions.add(exception("ON_ACCOUNT_WITHOUT_CHARGE", order, document.documentId(), null, null,
                        "The document is on account but no posted credit charge names it."));
            }
            if (DRAFT.equals(document.status()) && (closed
                    || (document.lastErrorAt() != null && document.lastErrorAt().isBefore(staleBefore)))) {
                exceptions.add(exception("DRAFT_STALE", order, document.documentId(), null, null, closed
                        ? "The order is closed but this document is still a draft."
                        : "The draft's issue failed at " + document.lastErrorAt() + " and was not retried."));
            }
        }
        for (Charge charge : order.charges()) {
            if (POSTED.equals(charge.status()) && !charge.debitPosted()) {
                exceptions.add(exception("CHARGE_DEBIT_MISSING", order, charge.documentId(), charge.chargeId(), null,
                        "The credit charge is posted but the customer's ledger has no debit for it."));
            }
            if (RESERVED.equals(charge.status()) && charge.createdAt().isBefore(staleBefore)) {
                exceptions.add(exception("CHARGE_RESERVED_STALE", order, charge.documentId(), charge.chargeId(), null,
                        "The credit charge has been reserved since " + charge.createdAt() + "."));
            }
        }
        return new Findings(List.copyOf(exceptions), List.copyOf(rounding));
    }

    private static java.util.Optional<DocumentException> unpaid(OrderFacts order, List<Document> issued) {
        Money zero = Money.of(BigDecimal.ZERO, order.currency());
        Money invoiced = issued.stream().map(Document::totalGross).reduce(zero, Money::plus);
        Money onAccount = order.charges().stream().filter(c -> POSTED.equals(c.status())).map(Charge::amount)
                .reduce(zero, Money::plus);
        Money covered = order.paidNet().plus(onAccount);
        if (covered.amount().compareTo(invoiced.amount()) >= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(exception("DOCUMENT_UNPAID", order, null, null, null, "The closed order's "
                + "issued documents total " + invoiced.amount() + " but it was paid " + order.paidNet().amount()
                + " and put on account " + onAccount.amount() + "."));
    }

    private static List<DocumentException> coverage(OrderFacts order) {
        List<DocumentException> mismatches = new ArrayList<>();
        Map<UUID, BigDecimal> covered = new TreeMap<>(order.issuedQuantity());
        for (Line line : order.lines()) {
            BigDecimal issued = covered.getOrDefault(line.lineId(), BigDecimal.ZERO);
            covered.remove(line.lineId());
            if (issued.compareTo(line.netQty()) != 0) {
                mismatches.add(exception("COVERAGE_MISMATCH", order, null, null, line.lineId(), "The line's net "
                        + "quantity is " + plain(line.netQty()) + " but its issued documents cover " + plain(issued)
                        + "."));
            }
        }
        covered.forEach((lineId, issued) -> {
            if (issued.signum() != 0) {
                mismatches.add(exception("COVERAGE_MISMATCH", order, null, null, lineId, "The issued documents "
                        + "cover " + plain(issued) + " of a line with nothing left to bill."));
            }
        });
        return mismatches;
    }

    private static void tax(OrderFacts order, List<Document> issued, List<DocumentException> exceptions,
                            List<ExplainedRoundingDifference> rounding) {
        Money zero = Money.of(BigDecimal.ZERO, order.currency());
        Map<BigDecimal, Money> lineTax = new TreeMap<>();
        order.lines().forEach(line -> lineTax.merge(line.ratePercent().stripTrailingZeros(), line.tax(), Money::plus));
        Map<BigDecimal, Money> documentTax = new TreeMap<>();
        issued.forEach(d -> d.taxByRate().forEach((rate, tax) -> documentTax.merge(rate.stripTrailingZeros(), tax,
                Money::plus)));
        Map<BigDecimal, List<Correction>> corrections = order.corrections().stream()
                .collect(Collectors.groupingBy(c -> c.ratePercent().stripTrailingZeros(), TreeMap::new,
                        Collectors.toList()));
        Map<BigDecimal, Money> unassigned = new TreeMap<>();
        order.unassignedTax().forEach((rate, amount) -> unassigned.merge(rate.stripTrailingZeros(), amount,
                Money::plus));
        Set<BigDecimal> rates = new java.util.TreeSet<>(lineTax.keySet());
        rates.addAll(documentTax.keySet());
        rates.addAll(corrections.keySet());
        rates.addAll(unassigned.keySet());
        for (BigDecimal rate : rates) {
            Money difference = lineTax.getOrDefault(rate, zero).minus(documentTax.getOrDefault(rate, zero));
            List<Correction> recorded = corrections.getOrDefault(rate, List.of());
            Money expected = recorded.stream().map(Correction::amount).reduce(zero, Money::plus);
            Money nobodys = unassigned.getOrDefault(rate, zero);
            if (nobodys.amount().signum() != 0) {
                exceptions.add(exception("TAX_DRIFT_UNASSIGNED", order, null, null, null, "At "
                        + rate.toPlainString() + "% settled voids left " + nobodys.amount() + " of tax that no "
                        + "residual document absorbed and no pending correction records."));
            } else if (difference.equals(expected)) {
                recorded.forEach(c -> explain(order, rate, c, exceptions, rounding));
            } else if (difference.amount().signum() != 0 || expected.amount().signum() != 0) {
                exceptions.add(exception("TAX_MISMATCH", order, null, null, null, "At " + rate.toPlainString()
                        + "% the lines' tax is " + lineTax.getOrDefault(rate, zero).amount()
                        + " but the issued documents carry " + documentTax.getOrDefault(rate, zero).amount()
                        + " (recorded pending corrections explain " + expected.amount() + ")."));
            }
        }
    }

    private static void explain(OrderFacts order, BigDecimal rate, Correction correction,
                                List<DocumentException> exceptions, List<ExplainedRoundingDifference> rounding) {
        if (correction.open()) {
            exceptions.add(exception("TAX_CORRECTION_PENDING", order, correction.documentId(), null,
                    correction.sourceLineId(), "The document lacks " + correction.amount().amount() + " of tax at "
                            + rate.toPlainString() + "%: it awaits the fiscal correction process (adjustment "
                            + correction.adjustmentId() + ")."));
        } else {
            rounding.add(new ExplainedRoundingDifference(order.orderId(), rate, correction.amount(),
                    correction.adjustmentId(), correction.correctionReference()));
        }
    }

    private static DocumentException exception(String kind, OrderFacts order, UUID documentId, UUID chargeId,
                                               UUID lineId, String detail) {
        return new DocumentException(kind, order.orderId(), documentId, chargeId, lineId, detail);
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
