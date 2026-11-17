/**
 * User accounts, employees, roles, store assignments, the POS PIN login and its sessions, support grants and approver
 * checks (spec §3, §5 "Organização e acessos", §10; D26-D28, D175-D177, D182, D183): Keycloak owns BackOffice
 * credentials, the second factor and BackOffice sessions. Builds the {@link com.xavier.backend.access.Actor}
 * principal that {@link com.xavier.backend.access.AccessPolicy} reads. Public API: this root package
 * ({@link com.xavier.backend.identity.ApproverVerifier}, {@link com.xavier.backend.identity.EmployeeDirectory}).
 * Everything else is internal. Uses organization only through {@code OrganizationApi} and its {@code dto} and
 * {@code event} named interfaces (listed explicitly: {@code "organization"} alone allows only its root package).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "organization :: event", "tenancy",
        "tenancy :: dto"})
package com.xavier.backend.identity;

import org.springframework.modulith.ApplicationModule;
