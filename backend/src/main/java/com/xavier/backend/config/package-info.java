/**
 * Cross-cutting web infrastructure: security filter chains, correlation id, the POS change stream and the system
 * endpoints (event publications, D88, audited). Not a business module; nothing depends on it.
 */
@ApplicationModule(displayName = "Configuration", allowedDependencies = {"shared", "access", "audit", "tenancy",
        "tenancy :: dto"})
package com.xavier.backend.config;

import org.springframework.modulith.ApplicationModule;
