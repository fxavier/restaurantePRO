package com.xavier.backend.settlement.reconciliation.model;

/** Where a statement came from (D78): an uploaded canonical CSV, or a provider's {@code StatementSource}. */
public enum BatchSource {
    CSV,
    PROVIDER
}
