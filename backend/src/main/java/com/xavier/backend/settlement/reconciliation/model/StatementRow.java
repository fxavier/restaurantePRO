package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.shared.Money;
import java.time.Instant;

/**
 * A checked statement line (D78): {@code lineNo} counts the data lines from 1 (the CSV header is not counted);
 * {@code amount} is positive and {@code fee} zero or more; blank texts are {@code null}.
 */
public record StatementRow(int lineNo, Instant occurredAt, Money amount, Money fee, String reference,
                           String authorizationCode, String lineRef) {
}
