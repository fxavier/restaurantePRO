package com.xavier.backend.organization.model.dto;

import java.util.UUID;

/**
 * A terminal without any secret. {@code status} is {@code REGISTERED} (not enrolled yet), {@code ACTIVE}
 * (enrolled) or {@code REVOKED}.
 */
public record TerminalSummary(UUID id, UUID storeId, String code, String name, String status) {
}
