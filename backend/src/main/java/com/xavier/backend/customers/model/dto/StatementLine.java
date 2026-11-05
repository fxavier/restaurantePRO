package com.xavier.backend.customers.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One ledger entry of a statement: {@code balance} is the running balance after it; {@code open} is what a sale
 * debit still owes now (null for other entries); {@code settlements} are the links the entry takes part in.
 */
public record StatementLine(UUID entryId, String kind, String sourceType, UUID sourceId, UUID documentId,
                            UUID storeId, LocalDate businessDate, String description, Instant occurredAt,
                            Money amount, Money balance, Money open, List<SettlementLink> settlements) {
}
