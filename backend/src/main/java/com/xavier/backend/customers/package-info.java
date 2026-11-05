/**
 * Customers and their credit ("Clientes e crédito", "conta corrente", spec §5, §7.7, §8.4; D64, D72, D73): customer
 * identity with normalized phones, addresses and a fiscal id; candidate search by phone suffix, name or tax id that
 * never selects a person (§5); and the company-wide credit account (P38) with its limit, block, limit reservations,
 * the append-only ledger and the settlements of debits by credits. The balance is never stored: it is the sum of
 * the ledger (D73).
 *
 * <p>Public API: this root package ({@link com.xavier.backend.customers.CustomersApi} and its records, for
 * settlement, which coordinates credit sales and debt payments under the D76 lock order) plus the {@code model.dto}
 * named interface. Allowed dependencies follow D65: never orders, settlement, fiscal, cash or production.
 */
@ApplicationModule(displayName = "Customers", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto"})
package com.xavier.backend.customers;

import org.springframework.modulith.ApplicationModule;
