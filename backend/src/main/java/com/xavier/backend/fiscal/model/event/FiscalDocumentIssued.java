package com.xavier.backend.fiscal.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A document was issued (committed with it). Production prints {@code printout} as the ORIGINAL on the printer of
 * {@code terminalId} (the terminal the issue ran on; {@code null} when it ran without one).
 */
@NamedInterface("event")
public record FiscalDocumentIssued(UUID documentId, UUID storeId, UUID terminalId, DocumentPrintout printout) {
}
