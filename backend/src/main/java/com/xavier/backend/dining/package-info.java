/**
 * Dining (spec §5 "Atendimento", §8.1, §8.2, §8.3): room areas and restaurant tables of a store, table occupations
 * (OPEN → CLOSED), the table lock indicator and the POS table map. Public API: {@link com.xavier.backend.dining.DiningApi}
 * (occupations, for orders) and the {@code model.dto} named interface. Stores and orders are referenced by id
 * (D6: no cross-module foreign keys); stores are checked through {@code OrganizationApi}. D99: a reservation may name
 * a customer, read through {@code CustomersApi}.
 */
@ApplicationModule(displayName = "Dining", allowedDependencies = {"shared", "audit", "access", "organization", "customers"})
package com.xavier.backend.dining;

import org.springframework.modulith.ApplicationModule;
