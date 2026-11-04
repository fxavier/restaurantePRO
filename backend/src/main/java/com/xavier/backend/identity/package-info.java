/**
 * User accounts, employees, roles, store assignments, BackOffice and POS login, sessions and approver checks
 * (spec §3, §5 "Organização e acessos", §10; D25-D28). Builds the {@link com.xavier.backend.access.Actor}
 * principal that {@link com.xavier.backend.access.AccessPolicy} reads. Public API: this root package
 * ({@link com.xavier.backend.identity.ApproverVerifier}). Everything else is internal. Uses organization only
 * through {@code OrganizationApi} and its {@code dto} and {@code event} named interfaces (listed explicitly:
 * {@code "organization"} alone allows only its root package).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "organization :: event"})
package com.xavier.backend.identity;

import org.springframework.modulith.ApplicationModule;
