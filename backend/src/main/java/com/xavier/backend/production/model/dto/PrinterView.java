package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.PrinterMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A printer as BackOffice shows and edits it ({@code version} for the next PUT), with its profile and what its last
 * send learnt ({@code lastStatus}, e.g. PAPER_NEAR_END; {@code null} before any send).
 */
public record PrinterView(UUID id, UUID storeId, String code, String name, String host, int port, CodePage codePage,
                          boolean active, PrinterMode mode, UUID redirectPrinterId, long version,
                          PrinterProfileView profile, String lastStatus, Instant lastStatusAt) {
}
