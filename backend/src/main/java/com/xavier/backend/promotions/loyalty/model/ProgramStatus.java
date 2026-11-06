package com.xavier.backend.promotions.loyalty.model;

/** A loyalty program version's life (D111): edited as DRAFT, in force while PUBLISHED, then RETIRED for good. */
public enum ProgramStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
