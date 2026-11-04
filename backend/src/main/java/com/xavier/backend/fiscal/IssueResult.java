package com.xavier.backend.fiscal;

/**
 * What an adapter returns for an issued document: its own data (for {@code internal}, the SHA-256 chain link) and
 * an optional QR payload to print ({@code null} when the regime has none).
 */
public record IssueResult(String adapterData, String qrPayload) {
}
