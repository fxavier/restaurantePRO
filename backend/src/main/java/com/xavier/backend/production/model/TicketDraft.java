package com.xavier.backend.production.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A ticket about to be created: its dedupe key ({@code sourceType}, {@code sourceId}, {@code destination}) and its
 * printout. Line breaks inside a line become spaces, so a stored ticket always reads back as the same lines.
 */
public record TicketDraft(UUID storeId, TicketSource sourceType, UUID sourceId, String destination, JobKind kind,
                          UUID orderId, UUID terminalId, List<String> lines, String qrPayload) {

    public TicketDraft {
        Objects.requireNonNull(storeId, "storeId");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(kind, "kind");
        lines = lines.stream().map(line -> line == null ? "" : line.replaceAll("[\\r\\n]+", " ")).toList();
    }
}
