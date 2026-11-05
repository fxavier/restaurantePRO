package com.xavier.backend.identity.model;

/** ACTIVE → EXPIRED (by time) or ACTIVE → REVOKED (r3-decisions §8.3). */
public enum SupportGrantStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
