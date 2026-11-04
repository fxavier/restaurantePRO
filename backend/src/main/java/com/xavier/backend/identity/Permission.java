package com.xavier.backend.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Code-defined capabilities (D27). Roles (data, S2) are named sets of these. Later runs append
 * constants; never rename or remove one (role data refers to them by name).
 *
 * <p>Granted-authority format (the contract between login in S2 and {@link AccessPolicy}):
 * {@code PERM_<NAME>} = installation-wide, {@code PERM_<NAME>@<storeId>} = only in that store.
 */
public enum Permission {
    COMPANY_MANAGE,
    STORE_MANAGE,
    TERMINAL_MANAGE,
    USER_MANAGE,
    ROLE_MANAGE,
    EMPLOYEE_MANAGE,
    DINING_MANAGE,
    DINING_VIEW,
    CATALOG_MANAGE,
    CATALOG_PUBLISH,
    CATALOG_VIEW,
    AUDIT_VIEW,
    /** Actuator metrics/info/prometheus on the management port (D33). */
    SYSTEM_MONITOR;

    private static final String PREFIX = "PERM_";

    /** Installation-wide authority, e.g. {@code PERM_CATALOG_PUBLISH}. */
    public String authority() {
        return PREFIX + name();
    }

    /** Store-scoped authority, e.g. {@code PERM_CATALOG_PUBLISH@0190...}. */
    public String authority(UUID storeId) {
        return authority() + "@" + Objects.requireNonNull(storeId, "storeId");
    }
}
