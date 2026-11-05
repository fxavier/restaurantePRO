package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.PrinterMode;
import java.util.UUID;

/** A printer as BackOffice shows and edits it ({@code version} for the next PUT). */
public record PrinterView(UUID id, UUID storeId, String code, String name, String host, int port, CodePage codePage,
                          boolean active, PrinterMode mode, UUID redirectPrinterId, long version) {
}
