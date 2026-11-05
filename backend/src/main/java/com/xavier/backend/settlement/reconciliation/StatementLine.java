package com.xavier.backend.settlement.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of a provider statement (D78): what the provider says it took. {@code amount} is positive; {@code fee}
 * (may be {@code null}: none) is what the provider kept; {@code reference} is its transaction reference (or ours, the
 * attempt id, for an integrated charge), {@code authorizationCode} the terminal's approval code, and {@code lineRef}
 * the provider's own id for the line. The same columns as the canonical CSV (P37).
 */
public record StatementLine(Instant occurredAt, BigDecimal amount, BigDecimal fee, String reference,
                            String authorizationCode, String lineRef) {
}
