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
 * refunds (every target), its credit charges, and per rate the tax drift settlement's split leaves (D68
 * amendment: its lines' tax minus its documents', non-zero only where a settled void emptied a partitioned pool).
 */
public record OrderFacts(UUID orderId, String lifecycle, Currency currency, List<Line> lines,
                         List<Document> documents, Map<UUID, BigDecimal> issuedQuantity, Money paidNet,
                         List<Charge> charges, Map<BigDecimal, Money> expectedTaxDrift) {

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
