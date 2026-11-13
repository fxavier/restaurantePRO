/**
 * Operational alerts (D149, spec §12): one contract, two outlets. Each module implements {@link
 * com.xavier.backend.shared.ops.AlertSource} next to its own data; {@code config} aggregates every source for the
 * in-app list, and Prometheus scrapes the matching metrics. Part of the OPEN {@code shared} kernel, so a module
 * contributes an alert without a new module edge; this package depends on no module ({@code ArchitectureTests}).
 */
package com.xavier.backend.shared.ops;
