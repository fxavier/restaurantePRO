package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A fiscal document with its snapshots, lines and tax summary per rate. {@code status} is {@code DRAFT} or
 * {@code ISSUED}; {@code seriesCode}, {@code number}, {@code issuedAt}, {@code adapterCode} and {@code nonFiscal}
 * are set once it is issued. {@code lastError} is the reason the last issue attempt failed (a visible pendency).
 * {@code orderId} is the order the document belongs to (its source for an ORDER document); {@code businessDate} is
 * {@code null} for an R2 document; {@code onAccount} marks a document with a credit share of {@code customerId} (R3,
 * D97 rule 5); {@code payments} is its payment snapshot (D97), {@code null} until settlement records it before the
 * issue, and for a document issued before D97. {@code createdAt} is when it was first drafted.
 */
public record DocumentView(UUID id, UUID storeId, String documentType, String status, String sourceType,
                           UUID sourceId, String seriesCode, Long number, Instant issuedAt, UUID issuedByEmployeeId,
                           String companyName, String companyTaxId, String companyCountry, String storeCode,
                           String storeName, Buyer buyer, List<Line> lines, List<TaxSummary> taxes,
                           Money totalGross, Money totalNet, Money totalTax, String adapterCode, Boolean nonFiscal,
                           String lastError, Instant lastErrorAt, Long version, UUID orderId,
                           LocalDate businessDate, UUID customerId, boolean onAccount,
                           PaymentSnapshot payments, Instant createdAt) {

    /**
     * One document line. {@code unitPrice} is the effective unit price its amounts follow (D101); {@code listUnitPrice}
     * and {@code discountLabel} are the price before the discount and its label, {@code null} when the line has none
     * (and on every document drafted before R4).
     */
    public record Line(int lineNo, UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                       BigDecimal taxRatePercent, Money gross, Money net, Money tax, Money listUnitPrice,
                       String discountLabel) {

        /** A line with no discount. */
        public Line(int lineNo, UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                    BigDecimal taxRatePercent, Money gross, Money net, Money tax) {
            this(lineNo, sourceLineId, description, quantity, unitPrice, taxRatePercent, gross, net, tax, null, null);
        }
    }

    /**
     * Σ of the lines' net and tax for one rate (D59: no re-rounding of totals). {@code taxCode} names the lines'
     * catalog tax code(s) at that rate, sorted and joined with "+" when two codes share it; {@code null} when the
     * lines carried none (and for documents drafted before the code was recorded).
     */
    public record TaxSummary(String taxCode, BigDecimal rate, Money net, Money tax) {
    }
}
