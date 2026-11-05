package com.xavier.backend.fiscal;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * What an adapter needs to issue one document. {@code previousAdapterData} is the adapter data of the previous
 * number in the same series, or {@code null} for the series' first number (the {@code internal} hash chain).
 * {@code onAccount} marks a credit sale (R3, A20): no payment is expected at issue.
 */
public record IssueRequest(UUID documentId, String documentType, String seriesCode, long number, Instant issuedAt,
                           Money totalGross, Money totalTax, String previousAdapterData, boolean onAccount) {
}
