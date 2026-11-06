package com.xavier.backend.settlement.reconciliation.model.dto;

/** Why a statement was refused: the data line (0: the header or the request), the column and the reason. */
public record StatementError(int line, String field, String message) {
}
