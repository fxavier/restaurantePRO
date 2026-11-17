package com.xavier.backend.shared.ops;

import java.util.List;

/**
 * A module's contribution to the operational alerts (D149). Implemented next to the data it reads (its own tables
 * or an in-memory gauge); {@code config} collects every bean as {@code List<AlertSource>}.
 */
public interface AlertSource {

    /**
     * The alerts active now; empty when all is well. Must be fast: it reads a gauge snapshot or runs one indexed
     * query.
     * <p>
     * Answers for the bound tenant only (security rule 7, D186): a source that keeps state of several tenants (a
     * gauge refreshed per tenant) returns the bound tenant's entry, never another tenant's. Only installation-wide
     * conditions (disk, backups, row-level security, the event registry) carry no tenant and show in every tenant.
     */
    List<OperationalAlert> alerts();
}
