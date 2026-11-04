/**
 * Dining configuration (spec §5 "Atendimento", §8.1, §8.2): room areas and restaurant tables of a store, and
 * the POS table map. Table occupation arrives in R2. Public API: the {@code model.dto} named interface.
 * Stores are referenced by id and checked through {@code OrganizationApi} (D6: no cross-module foreign keys).
 */
@ApplicationModule(displayName = "Dining", allowedDependencies = {"shared", "audit", "access", "organization"})
package com.xavier.backend.dining;

import org.springframework.modulith.ApplicationModule;
