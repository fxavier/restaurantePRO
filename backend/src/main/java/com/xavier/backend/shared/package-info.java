/**
 * Technical kernel (D14): {@code Money}, base exceptions + ProblemDetail mapping, idempotency, the
 * {@code Clock} bean, pagination. No business rules. OPEN: every module may use every type here, and
 * {@code shared} depends on no other module.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package com.xavier.backend.shared;

import org.springframework.modulith.ApplicationModule;
