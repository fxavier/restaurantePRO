package com.xavier.backend.settlement.model;

/** The order's sale document as settlement reports it (spec §4: issue, payment and settlement are distinct states). */
public enum FiscalState {
    /** No document yet. */
    NONE,
    /** A draft that is not issued yet: a visible fiscal pendency (D50 §7.6). */
    DRAFT,
    ISSUED
}
