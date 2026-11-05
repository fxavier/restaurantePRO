package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.shared.Money;
import java.time.Instant;

/**
 * A checked statement line (D78): {@code lineNo} is the physical line of the CSV file (its header is line 1), or the
 * position of a provider line from 1;
 * {@code amount} is positive and {@code fee} zero or more; blank texts are {@code null}.
 */
public record StatementRow(int lineNo, Instant occurredAt, Money amount, Money fee, String reference,
                           String authorizationCode, String lineRef) {
}
