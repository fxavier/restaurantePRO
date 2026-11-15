package com.xavier.backend.config.security;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Instant;
import java.util.UUID;

/**
 * Fixtures of the resolution pipeline, written as the owner (it bypasses row-level security, so every row names its
 * tenant): ACTIVE tenants, local accounts and roles of a tenant, and memberships in {@code tenancy} (PLATFORM; the
 * application role cannot write them), as onboarding and the BackOffice would create them.
 */
public final class Members {

    private Members() {
    }

    /** A new ACTIVE tenant (random id). */
    public static UUID activeTenant() {
        UUID id = UUID.randomUUID();
        TenantTestSupport.ensureTenant(id, "t-" + id, "ACTIVE");
        return id;
    }

    /** A role of {@code tenant} with {@code permissions}. */
    public static UUID role(UUID tenant, Permission... permissions) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().tx(jdbc -> {
            jdbc.sql("INSERT INTO identity.role (id, tenant_id, name, version, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 0, now(), now())").param(id).param(tenant).param("role-" + id).update();
            for (Permission permission : permissions) {
                jdbc.sql("INSERT INTO identity.role_permission (tenant_id, role_id, permission) VALUES (?, ?, ?)")
                        .param(tenant).param(id).param(permission.name()).update();
            }
        });
        return id;
    }

    /** An active ordinary account of {@code tenant} holding {@code roles} tenant-wide. */
    public static UUID account(UUID tenant, UUID... roles) {
        return account(tenant, false, roles);
    }

    /** An active account of {@code tenant}; {@code support} marks a support account (D83). */
    public static UUID account(UUID tenant, boolean support, UUID... roles) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().tx(jdbc -> {
            jdbc.sql("INSERT INTO identity.user_account (id, tenant_id, username, active, support, version, "
                    + "created_at, updated_at) VALUES (?, ?, ?, true, ?, 0, now(), now())")
                    .param(id).param(tenant).param("u-" + id).param(support).update();
            for (UUID role : roles) {
                jdbc.sql("INSERT INTO identity.user_account_role (tenant_id, user_account_id, role_id) "
                        + "VALUES (?, ?, ?)").param(tenant).param(id).param(role).update();
            }
        });
        return id;
    }

    public static void deactivate(UUID account) {
        OwnerDatabase.shared().jdbc().sql("UPDATE identity.user_account SET active = false WHERE id = ?")
                .param(account).update();
    }

    /** An ACTIVE {@code ACCOUNT} membership of the tenant realm's {@code subject}. */
    public static UUID accountMembership(UUID tenant, String subject, UUID account) {
        return membership(tenant, TestJwtFactory.TENANT_ISSUER, subject, "ACCOUNT", account,
                Instant.now().minusSeconds(60), null);
    }

    public static UUID membership(UUID tenant, String issuer, String subject, String kind, UUID principal,
                                  Instant validFrom, Instant validUntil) {
        UUID id = UUID.randomUUID();
        OwnerDatabase.shared().jdbc().sql("INSERT INTO tenancy.tenant_membership (id, tenant_id, issuer, subject, kind, "
                        + "principal_id, status, valid_from, valid_until, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, now())")
                .param(id).param(tenant).param(issuer).param(subject).param(kind).param(principal)
                .param(java.sql.Timestamp.from(validFrom))
                .param(validUntil == null ? null : java.sql.Timestamp.from(validUntil)).update();
        return id;
    }

    public static void revoke(UUID membership) {
        OwnerDatabase.shared().jdbc().sql("UPDATE tenancy.tenant_membership SET status = 'REVOKED', revoked_at = now() "
                + "WHERE id = ?").param(membership).update();
    }

    /** A tenant with one account whose Keycloak user is {@code subject}. @return the account */
    public static UUID member(UUID tenant, String subject, UUID... roles) {
        UUID account = account(tenant, roles);
        accountMembership(tenant, subject, account);
        return account;
    }
}
