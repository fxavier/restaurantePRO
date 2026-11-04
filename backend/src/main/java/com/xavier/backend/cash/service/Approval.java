package com.xavier.backend.cash.service;

import java.util.UUID;

/**
 * Phase 1 of an approved command (D40): {@code approverEmployeeId} vouched for {@code dayId}, checked outside any
 * transaction. Phase 2 applies the command with it.
 */
public record Approval(UUID dayId, UUID approverEmployeeId) {
}
