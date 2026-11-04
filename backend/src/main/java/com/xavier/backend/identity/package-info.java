/**
 * Users, employees, RBAC, sessions (spec §6). S0 ships only the public authorization contract in this
 * package: {@link com.xavier.backend.identity.Permission}, {@link com.xavier.backend.identity.Actor},
 * {@link com.xavier.backend.identity.AccessPolicy}. S2 fills in the rest without changing it.
 *
 * <p>D13 allows identity → shared, audit, organization. Modulith rejects a dependency on a module that
 * does not exist yet, so S2 adds {@code "organization"} here once S1 has created that module.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"shared", "audit"})
package com.xavier.backend.identity;

import org.springframework.modulith.ApplicationModule;
