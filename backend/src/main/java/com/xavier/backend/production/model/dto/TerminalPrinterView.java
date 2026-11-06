package com.xavier.backend.production.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A terminal → the printer its subtotals and documents go to. */
public record TerminalPrinterView(@NotNull UUID terminalId, @NotNull UUID printerId) {
}
