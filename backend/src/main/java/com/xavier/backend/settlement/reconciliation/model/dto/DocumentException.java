package com.xavier.backend.settlement.reconciliation.model.dto;

import java.util.UUID;

/**
 * One D80 exception: a fact that should not exist (a bug alarm) or a pendency stuck for over an hour. {@code kind}
 * is DOCUMENT_UNPAID, ON_ACCOUNT_WITHOUT_CHARGE, CHARGE_DEBIT_MISSING, COVERAGE_MISMATCH, CHARGE_RESERVED_STALE,
 * DRAFT_STALE, TAX_MISMATCH, TAX_CORRECTION_PENDING (D96: an open pending fiscal correction of {@code documentId} for
 * {@code lineId}'s tax cent, its amount in {@code detail}) or TAX_DRIFT_UNASSIGNED (D96: a closed order's settled-void
 * cent no adjustment records, a bug alarm); the ids name what it is about.
 */
public record DocumentException(String kind, UUID orderId, UUID documentId, UUID chargeId, UUID lineId,
                                String detail) {
}
