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
 * issue, and for a document issued before D97.
 */
public record DocumentView(UUID id, UUID storeId, String documentType, String status, String sourceType,
                           UUID sourceId, String seriesCode, Long number, Instant issuedAt, UUID issuedByEmployeeId,
                           String companyName, String companyTaxId, String companyCountry, String storeCode,
                           String storeName, Buyer buyer, List<Line> lines, List<TaxSummary> taxes,
                           Money totalGross, Money totalNet, Money totalTax, String adapterCode, Boolean nonFiscal,
                           String lastError, Instant lastErrorAt, Long version, UUID orderId,
                           LocalDate businessDate, UUID customerId, boolean onAccount,
                           PaymentSnapshot payments) {

    public record Line(int lineNo, UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                       BigDecimal taxRatePercent, Money gross, Money net, Money tax) {
    }

    /** Σ of the lines' net and tax for one rate (D59: no re-rounding of totals). */
    public record TaxSummary(BigDecimal rate, Money net, Money tax) {
    }
}
