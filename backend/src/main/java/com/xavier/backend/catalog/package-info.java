/**
 * Catalog (spec §5 "Catálogo", §8.3, D31): the editable working copy (families, units, tax categories and
 * rates, items, variants, barcodes, store price lists) and the frozen per-store releases the POS sells from.
 * Public API: the {@code model.dto} and {@code model.event} named interfaces. Everything else is internal.
 * {@code organization :: dto} is declared next to {@code organization} because {@code OrganizationApi} returns
 * {@code StoreSummary}; naming the module alone grants only its root package.
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = {"shared", "audit", "access", "organization", "organization :: dto"})
package com.xavier.backend.catalog;

import org.springframework.modulith.ApplicationModule;
