package com.xavier.backend.customers.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/**
 * A CREDIT settling a sale DEBIT, or, with {@code reversalEntryId}, that refund DEBIT reopening what the CREDIT had
 * settled on the debit.
 */
public record SettlementLink(UUID creditEntryId, UUID debitEntryId, UUID reversalEntryId, Money amount) {
}
