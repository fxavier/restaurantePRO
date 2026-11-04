/**
 * Company profile, stores, terminals (spec §2, §5 "Organização e acessos", §8.2). Public API: this root
 * package ({@link com.xavier.backend.organization.OrganizationApi},
 * {@link com.xavier.backend.organization.BusinessDays}) plus the {@code model.dto} and {@code model.event}
 * named interfaces. Everything else is internal.
 */
@ApplicationModule(displayName = "Organization", allowedDependencies = {"shared", "audit", "access"})
package com.xavier.backend.organization;

import org.springframework.modulith.ApplicationModule;
