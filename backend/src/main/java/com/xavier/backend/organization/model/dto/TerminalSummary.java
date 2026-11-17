package com.xavier.backend.organization.model.dto;

import com.xavier.backend.organization.model.TerminalStatus;
import java.util.UUID;

/**
 * A store device without any secret. {@code status} is {@code REGISTERED} (not enrolled yet), {@code ACTIVE}
 * (enrolled) or {@code REVOKED}; {@code kind} is {@code POS} (a terminal) or {@code PRINT_BRIDGE} (D187).
 */
public record TerminalSummary(UUID id, UUID storeId, String code, String name, String status, String kind) {

    /**
     * Enrolled and not revoked ({@code ACTIVE}). Derived, not a component: the terminal JSON keeps its R2 shape.
     */
    public boolean active() {
        return TerminalStatus.ACTIVE.name().equals(status);
    }

    /** An enrolled, not revoked print bridge. */
    public boolean activeBridge() {
        return active() && "PRINT_BRIDGE".equals(kind);
    }

    /** An enrolled, not revoked POS terminal. */
    public boolean activeTerminal() {
        return active() && "POS".equals(kind);
    }
}
