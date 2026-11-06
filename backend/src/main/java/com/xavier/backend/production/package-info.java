/**
 * Production (spec §4 "Produção e impressão", §8.3, §8.6; D34, D35, D56, D57): printers per store with their modes
 * (NORMAL, HOLD "Espera", IGNORE, REDIRECT), routes from a production destination or a terminal to a printer,
 * production tickets (one logical ticket per source × destination, the dedupe key) and the persistent print queue
 * of physical delivery attempts (print jobs), sent as ESC/POS over TCP 9100 by a worker that never holds a
 * transaction during the socket I/O.
 *
 * <p>Production consumes the {@code orders} and {@code fiscal} events (rounds, voids, subtotals, documents and
 * their copies) through idempotent listeners and tells orders that a round was received through
 * {@link com.xavier.backend.orders.OrdersProductionApi}. Nothing depends on production. Allowed dependencies follow
 * D35.
 */
@ApplicationModule(displayName = "Production", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "orders", "orders :: event",
        "fiscal :: event"})
package com.xavier.backend.production;

import org.springframework.modulith.ApplicationModule;
