package com.xavier.backend.shared.ops;

import java.util.List;

/**
 * A module's contribution to the operational alerts (D149). Implemented next to the data it reads (its own tables
 * or an in-memory gauge); {@code config} collects every bean as {@code List<AlertSource>}.
 */
public interface AlertSource {

    /** Who may see a source's alerts (R6 user decision: a tenant sees only its own alerts). */
    enum Scope {
        /** The bound tenant's own condition: shown to that tenant's {@code SYSTEM_MONITOR} holders only. */
        TENANT,
        /**
         * A condition of the platform (disk, backups, the event registry, row-level security): shown to platform
         * operators only ({@code /api/v1/platform/alerts}), never to a tenant user.
         */
        PLATFORM
    }

    /** Which list the source's alerts belong to; every source decides (no default, so none is shown by mistake). */
    Scope scope();

    /**
     * The alerts active now; empty when all is well. Must be fast: it reads a gauge snapshot or runs one indexed
     * query.
     * <p>
     * A {@link Scope#TENANT} source answers for the bound tenant only (security rule 7, D186): one that keeps state of
     * several tenants (a gauge refreshed per tenant) returns the bound tenant's entry, never another tenant's. A
     * {@link Scope#PLATFORM} source is called with no tenant bound.
     */
    List<OperationalAlert> alerts();
}
