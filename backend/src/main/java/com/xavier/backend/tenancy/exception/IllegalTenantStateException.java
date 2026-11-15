package com.xavier.backend.tenancy.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** D174: the tenant cannot make this transition ({@code ACTIVE ⇄ SUSPENDED}, {@code SUSPENDED → CLOSED} only). */
public class IllegalTenantStateException extends ConflictException {

    public static final String CODE = "ILLEGAL_TENANT_STATE";

    public IllegalTenantStateException() {
        super(CODE, "The tenant cannot make this change in its current status.");
    }
}
