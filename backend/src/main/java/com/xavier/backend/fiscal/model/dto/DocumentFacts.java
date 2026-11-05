package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The immutable facts of one document, as reporting projects them (D84). {@code businessDate} is the order's business
 * day given at draft time, or for an R2 document the business day of its issue in the store's zone and current
 * cutoff (D85). {@code orderId} is the order it belongs to; for a {@code PARTITION} source, {@code sourceId} is the
 * partition. {@code terminalId} is where it was issued. The issue fields are {@code null} on a DRAFT.
 */
public record DocumentFacts(UUID documentId, UUID storeId, String documentType, String status, String sourceType,
                            UUID sourceId, UUID orderId, LocalDate businessDate, Instant issuedAt, UUID terminalId,
                            UUID issuedByEmployeeId, UUID customerId, boolean onAccount, String seriesCode,
                            Long number, Boolean nonFiscal, Money totalGross, Money totalNet, Money totalTax,
                            List<DocumentView.Line> lines, List<DocumentView.TaxSummary> taxes) {
}
