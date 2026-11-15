package com.xavier.backend.tenancy.exception;

import com.xavier.backend.shared.exception.NotFoundException;

/** No tenant has this id (platform routes only: a tenant route never names a tenant, D177). */
public class TenantNotFoundException extends NotFoundException {

    public static final String CODE = "TENANT_NOT_FOUND";

    public TenantNotFoundException() {
        super(CODE, "No such tenant.");
    }
}
