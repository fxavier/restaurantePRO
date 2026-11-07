package com.xavier.backend.promotions.loyalty.model;

/** What a reward does to the one unit it is redeemed on (D112, P46): it becomes free, or costs an amount less. */
public enum RewardEffect {
    FREE_UNIT,
    AMOUNT_OFF_UNIT
}
