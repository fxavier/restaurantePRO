package com.xavier.backend.promotions.loyalty.model;

/** The kind of a points ledger entry (D110); its sign and source type follow from it (V12021 CHECKs). */
public enum EntryKind {
    EARN,
    REDEEM,
    EARN_REVERSAL,
    REDEEM_REVERSAL,
    ADJUSTMENT,
    EXPIRY
}
