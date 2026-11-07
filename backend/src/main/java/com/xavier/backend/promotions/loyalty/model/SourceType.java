package com.xavier.backend.promotions.loyalty.model;

/** What a ledger entry comes from: one entry per source id and kind ({@code loyalty_point_entry_source_uk}). */
public enum SourceType {
    ORDER,
    ORDER_LINE,
    ADJUSTMENT,
    EXPIRY
}
