package com.xavier.backend.settlement.reconciliation.model.dto;

import java.util.UUID;

/**
 * One D80 exception: a fact that should not exist (a bug alarm) or a pendency stuck for over an hour. {@code kind}
 * is DOCUMENT_UNPAID, ON_ACCOUNT_WITHOUT_CHARGE, CHARGE_DEBIT_MISSING, COVERAGE_MISMATCH, CHARGE_RESERVED_STALE,
 * DRAFT_STALE or TAX_MISMATCH; the ids name what it is about.
 */
public record DocumentException(String kind, UUID orderId, UUID documentId, UUID chargeId, UUID lineId,
                                String detail) {
}
