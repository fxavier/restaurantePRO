/**
 * Append-only functional audit (spec §10, D18, D20). Public API: {@link com.xavier.backend.audit.AuditLog}
 * and {@link com.xavier.backend.audit.AuditEntry} (this package). Everything in subpackages is internal.
 */
@ApplicationModule(displayName = "Audit", allowedDependencies = "shared")
package com.xavier.backend.audit;

import org.springframework.modulith.ApplicationModule;
