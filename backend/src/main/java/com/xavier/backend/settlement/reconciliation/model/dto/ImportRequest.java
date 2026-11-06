package com.xavier.backend.settlement.reconciliation.model.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A CSV statement upload as the Idempotency-Key hashes it: the form fields and the SHA-256 of the file, so a retry
 * with the same key and the same file replays, and another file under the same key is refused (D24).
 */
public record ImportRequest(UUID storeId, String methodCode, LocalDate periodFrom, LocalDate periodTo,
                            String fileSha256) {
}
