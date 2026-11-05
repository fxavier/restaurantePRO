package com.xavier.backend.settlement.service;

import java.util.UUID;

/**
 * Phase 1 of an approved command (D40): {@code approverEmployeeId} vouched for the reclassification of
 * {@code attemptId}, checked before any transaction or lock. Phase 2 applies it.
 */
public record Approval(UUID attemptId, UUID approverEmployeeId) {
}
