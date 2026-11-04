package com.xavier.backend.organization.model.dto;

import com.xavier.backend.organization.model.Terminal;
import java.util.UUID;

/**
 * A terminal without any secret. {@code status} is {@code REGISTERED} (not enrolled yet), {@code ACTIVE}
 * (enrolled) or {@code REVOKED}.
 */
public record TerminalSummary(UUID id, UUID storeId, String code, String name, String status) {

    public static TerminalSummary from(Terminal terminal) {
        return new TerminalSummary(terminal.getId(), terminal.getStoreId(), terminal.getCode(), terminal.getName(),
                terminal.getStatus().name());
    }
}
