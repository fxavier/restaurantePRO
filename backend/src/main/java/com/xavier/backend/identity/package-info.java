/**
 * Users, employees, RBAC, sessions (spec §6). S0 ships only the public authorization contract in this
 * package: {@link com.xavier.backend.identity.Permission}, {@link com.xavier.backend.identity.Actor},
 * {@link com.xavier.backend.identity.AccessPolicy}. S2 fills in the rest without changing it.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {"shared", "audit", "organization"})
package com.xavier.backend.identity;

import org.springframework.modulith.ApplicationModule;
