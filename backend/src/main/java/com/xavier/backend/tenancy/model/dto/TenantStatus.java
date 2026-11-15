package com.xavier.backend.tenancy.model.dto;

/** D174: {@code PROVISIONING → ACTIVE ⇄ SUSPENDED → CLOSED}; only ACTIVE tenants serve requests and run jobs. */
public enum TenantStatus {
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    CLOSED
}
