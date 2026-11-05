package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.PrinterMode;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code redirectPrinterId} only with REDIRECT. IGNORE also needs {@code PRODUCTION_SUPPRESS}. */
public record PrinterModeRequest(@NotNull PrinterMode mode, UUID redirectPrinterId, @NotNull Long version) {
}
