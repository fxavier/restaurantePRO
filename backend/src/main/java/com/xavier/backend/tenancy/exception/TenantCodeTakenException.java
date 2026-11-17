package com.xavier.backend.tenancy.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The tenant code (the Keycloak organization alias) already belongs to another company: onboarding replays only
 * with the same code and legal name. */
public class TenantCodeTakenException extends ConflictException {

    public static final String CODE = "TENANT_CODE_TAKEN";

    public TenantCodeTakenException() {
        super(CODE, "This tenant code belongs to another company.");
    }
}
