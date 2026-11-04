/**
 * Cross-cutting web infrastructure: security filter chains, correlation id. Not a business module;
 * nothing depends on it.
 */
@ApplicationModule(displayName = "Configuration", allowedDependencies = {"shared", "access"})
package com.xavier.backend.config;

import org.springframework.modulith.ApplicationModule;
