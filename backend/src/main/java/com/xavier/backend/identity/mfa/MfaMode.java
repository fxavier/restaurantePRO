package com.xavier.backend.identity.mfa;

/** The installation's MFA policy (D140, P52); a deployment property, never data. */
public enum MfaMode {
    /** No second factor anywhere (the R4 behaviour; the code default). */
    OFF,
    /** A second factor for a remote BackOffice login and for support accounts; step-up for sensitive permissions. */
    SENSITIVE,
    /** A second factor for every BackOffice login; step-up for sensitive permissions. */
    ALL_BACKOFFICE
}
