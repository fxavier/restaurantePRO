package com.xavier.backend.identity.model;

/** A second factor's life (D139, D143): PENDING → ACTIVE → REVOKED. A PENDING one is deleted when replaced. */
public enum MfaFactorStatus {
    PENDING,
    ACTIVE,
    REVOKED
}
