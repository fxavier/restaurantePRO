package com.xavier.backend.fiscal;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * What an adapter needs to issue one document. {@code previousAdapterData} is the adapter data of the previous
 * number in the same series, or {@code null} for the series' first number (the {@code internal} hash chain).
 */
public record IssueRequest(UUID documentId, String documentType, String seriesCode, long number, Instant issuedAt,
                           Money totalGross, Money totalTax, String previousAdapterData) {
}
