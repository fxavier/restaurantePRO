package com.xavier.backend.production.model.dto;

import java.util.List;
import java.util.UUID;

/** A store's terminal printers. */
public record TerminalPrintersView(UUID storeId, List<TerminalPrinterView> assignments) {
}
