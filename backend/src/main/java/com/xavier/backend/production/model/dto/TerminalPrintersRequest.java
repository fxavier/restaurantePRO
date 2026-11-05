package com.xavier.backend.production.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Every terminal printer of a store: it replaces the current assignments. */
public record TerminalPrintersRequest(@NotNull UUID storeId,
                                      @NotNull List<@Valid @NotNull TerminalPrinterView> assignments) {
}
