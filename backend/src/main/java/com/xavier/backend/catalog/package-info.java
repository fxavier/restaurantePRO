/**
 * Catalog (spec §5 "Catálogo", §8.3, D31): the editable working copy (families, units, tax categories and
 * rates, items, variants, barcodes, store price lists) and the frozen per-store releases the POS sells from.
 * Public API: the {@code model.dto} and {@code model.event} named interfaces. Everything else is internal.
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = {"shared", "audit", "access", "organization"})
package com.xavier.backend.catalog;

import org.springframework.modulith.ApplicationModule;
