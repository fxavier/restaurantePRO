/**
 * Append-only functional audit (spec §10, D18, D20, D41). Public API: {@link com.xavier.backend.audit.AuditLog}
 * (this package). Everything else is internal. Depends on {@code access} to attribute a row to the current actor
 * (D35 amendment).
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = {"shared", "access"})
package com.xavier.backend.audit;

import org.springframework.modulith.ApplicationModule;
