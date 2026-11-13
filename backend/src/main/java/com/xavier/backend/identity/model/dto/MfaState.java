package com.xavier.backend.identity.model.dto;

/** Where a BackOffice session stands with its second factor (D141). */
public enum MfaState {
    /** No second factor was asked at login (mode {@code OFF}, or a LAN login in {@code SENSITIVE}). */
    NONE,
    /** The password was right; the session holds no authority until {@code POST /auth/mfa/verify}. */
    PENDING,
    /** A second factor is needed but the account has none; the session holds no authority until it enrols. */
    ENROLMENT_REQUIRED,
    /** The session proved its second factor at login or at enrolment. */
    VERIFIED
}
