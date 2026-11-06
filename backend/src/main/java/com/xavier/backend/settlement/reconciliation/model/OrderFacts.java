package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the documents control (D80) knows of one order: its lifecycle ({@code OPEN}, {@code CLOSED}, ...), its billable
 * lines, its DRAFT and ISSUED documents, the quantity its ISSUED documents cover per line, what was paid net of
 * refunds (every target), its credit charges, its recorded pending fiscal corrections (D96: open or resolved, the only
 * explanation of a tax difference) and per rate the settled voids' tax drift no adjustment records ({@code unassigned},
 * D96 rule 7: zero once the residual is drafted or the order closed).
 */
public record OrderFacts(UUID orderId, String lifecycle, Currency currency, List<Line> lines,
                         List<Document> documents, Map<UUID, BigDecimal> issuedQuantity, Money paidNet,
                         List<Charge> charges, List<Correction> corrections, Map<BigDecimal, Money> unassignedTax) {

    /**
     * A PENDING_CORRECTION adjustment (D96): {@code amount} of tax at {@code ratePercent} of {@code sourceLineId} that
     * {@code documentId} lacks; {@code correctionReference} is its resolution's, {@code null} while it is open.
     */
    public record Correction(UUID adjustmentId, BigDecimal ratePercent, UUID sourceLineId, UUID documentId,
                             Money amount, String correctionReference) {

        public boolean open() {
            return correctionReference == null;
        }
    }

    /** A billable line: its net quantity and the tax the order computes for it. */
    public record Line(UUID lineId, BigDecimal netQty, BigDecimal ratePercent, Money tax) {
    }

    /** {@code taxByRate} is the document's tax summary; {@code lastErrorAt} its last failed issue, if any. */
    public record Document(UUID documentId, String status, String sourceType, UUID sourceId, boolean onAccount,
                           Money totalGross, Map<BigDecimal, Money> taxByRate, Instant lastErrorAt) {
    }

    /** A credit charge (RESERVED, POSTED or RELEASED); {@code debitPosted}: its ledger debit exists. */
    public record Charge(UUID chargeId, String status, UUID documentId, Money amount, Instant createdAt,
                         boolean debitPosted) {
    }
}
