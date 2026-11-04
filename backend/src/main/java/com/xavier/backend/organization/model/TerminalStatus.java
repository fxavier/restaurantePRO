package com.xavier.backend.organization.model;

/** REGISTERED -> ACTIVE (enrolled) -> REVOKED; REGISTERED -> REVOKED. REVOKED is final. */
public enum TerminalStatus {
    REGISTERED,
    ACTIVE,
    REVOKED
}
