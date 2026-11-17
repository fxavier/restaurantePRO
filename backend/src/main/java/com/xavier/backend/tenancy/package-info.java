/**
 * Tenants and memberships (R6, D174, D177, D181 as amended): who is a tenant, which (issuer, subject) belongs to
 * which tenant, and the tenant lifecycle. Public API: {@link com.xavier.backend.tenancy.TenancyApi}, the
 * {@link com.xavier.backend.tenancy.IdentityProviderAdmin} port, and the {@code model.dto} and {@code model.event}
 * named interfaces. Its tables are PLATFORM class and the application role holds no privilege on them: every read and
 * write goes through a SECURITY DEFINER function (V15001 to V15003). Three modules use it: {@code config} (membership
 * resolution), {@code identity} (accounts' and support memberships, onboarding's administrator) and
 * {@code organization} (device enrolment and its memberships); every other module sees the tenant only through
 * {@code shared.tenancy.TenantContext} (D14). Onboarding is a chain of events (D181 amended):
 * {@code TenantProvisioned} → organization → {@code CompanyProvisioned} → identity →
 * {@link TenancyApi#activate}.
 */
@ApplicationModule(displayName = "Tenancy", allowedDependencies = {"shared", "audit"})
package com.xavier.backend.tenancy;

import org.springframework.modulith.ApplicationModule;
