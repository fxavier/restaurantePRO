package com.xavier.backend.organization.model.dto;

import com.xavier.backend.organization.model.TerminalStatus;
import java.util.UUID;

/**
 * A terminal without any secret. {@code status} is {@code REGISTERED} (not enrolled yet), {@code ACTIVE}
 * (enrolled) or {@code REVOKED}.
 */
public record TerminalSummary(UUID id, UUID storeId, String code, String name, String status) {

    /**
     * Enrolled and not revoked ({@code ACTIVE}). Derived, not a component: the terminal JSON keeps its R2 shape.
     */
    public boolean active() {
        return TerminalStatus.ACTIVE.name().equals(status);
    }
}
