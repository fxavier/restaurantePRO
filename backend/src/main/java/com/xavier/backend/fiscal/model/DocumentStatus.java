package com.xavier.backend.fiscal.model;

/** spec §8.3: DRAFT → ISSUED. There is no generic CANCELLED; corrections are new documents (market run, R3). */
public enum DocumentStatus {
    DRAFT,
    ISSUED
}
