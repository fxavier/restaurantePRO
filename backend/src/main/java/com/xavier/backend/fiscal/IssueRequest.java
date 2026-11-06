package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * What an adapter needs to issue one document. {@code previousAdapterData} is the adapter data of the previous
 * number in the same series, or {@code null} for the series' first number (the {@code internal} hash chain).
 * {@code onAccount} marks a document with a credit share (R3, D97 rule 5); {@code payments} is its payment
 * breakdown at issue (D97), {@code null} only for a document issued before it existed. Neither enters the internal
 * hash chain, whose input stays the one of D54.
 */
public record IssueRequest(UUID documentId, String documentType, String seriesCode, long number, Instant issuedAt,
                           Money totalGross, Money totalTax, String previousAdapterData, boolean onAccount,
                           PaymentSnapshot payments) {
}
