package com.xavier.backend.customers;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A credit ledger entry. {@code kind} is DEBIT or CREDIT; {@code sourceType} is CREDIT_CHARGE (a credit sale, with
 * its {@code documentId}), PAYMENT (a debt payment) or REFUND (of a debt payment; no business date).
 */
public record LedgerEntryView(UUID id, UUID customerId, String kind, String sourceType, UUID sourceId,
                              UUID documentId, UUID storeId, LocalDate businessDate, Money amount, String description,
                              Instant occurredAt) {
}
