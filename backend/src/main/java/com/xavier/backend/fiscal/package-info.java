/**
 * Fiscal documents (spec §5 "Fiscal", §8.3, D54, P7, P8): per-store series with gapless numbering, the generic
 * {@code SALE} document (DRAFT → ISSUED) with its lines and tax summary, reprints as copies, and the
 * {@link com.xavier.backend.fiscal.FiscalAdapter} port with the non-certified {@code internal} adapter (B4).
 * Public API: this root package ({@link com.xavier.backend.fiscal.FiscalApi}, the adapter SPI types and
 * {@link com.xavier.backend.fiscal.FiscalIssueFailedException}) plus the {@code model.dto} and {@code model.event}
 * named interfaces. A document keeps snapshots of company, store and buyer and never reads the current catalog.
 */
@ApplicationModule(displayName = "Fiscal",
        allowedDependencies = {"shared", "audit", "access", "organization", "organization :: dto"})
package com.xavier.backend.fiscal;

import org.springframework.modulith.ApplicationModule;
