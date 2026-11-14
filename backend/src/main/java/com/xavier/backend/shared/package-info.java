/**
 * Technical kernel (D14): {@code Money}, base exceptions + ProblemDetail mapping, idempotency, the
 * {@code Clock} bean, pagination. No business rules. OPEN: every module may use every type here. {@code shared}
 * depends on no business module; its one dependency is on {@code access}'s store-scope annotations
 * ({@code @SystemScoped} and {@code SystemScope}), which its own jobs carry (D166, ADR 0039). {@code access} uses only
 * {@code shared.persistence.StoreScopeViolations} back, and {@code ApplicationModules.verify()} accepts both.
 */
@ApplicationModule(displayName = "Shared kernel", type = ApplicationModule.Type.OPEN)
package com.xavier.backend.shared;

import org.springframework.modulith.ApplicationModule;
