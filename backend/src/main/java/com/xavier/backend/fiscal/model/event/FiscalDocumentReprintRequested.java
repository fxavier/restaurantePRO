package com.xavier.backend.fiscal.model.event;

import java.util.UUID;

/** A copy of an issued document was requested (spec §8.4: a reprint is a copy, never a new document). */
public record FiscalDocumentReprintRequested(UUID documentId, int copyNo, UUID storeId, UUID terminalId,
                                             DocumentPrintout printout) {
}
