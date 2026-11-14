package com.xavier.backend.access;

/**
 * The reviewed allow-list of every actor-less store scope ("âmbito de sistema", D166): jobs, the bootstrap, the
 * readiness hook, DB-backed gauges and the alert aggregation. A new constant, or a new reach of ALL, is a visible diff
 * in review ({@code SystemScopeRegistryTest}). Applied with {@link SystemScoped} or {@link StoreScopes#run}.
 */
public enum SystemScope {
    BOOTSTRAP(Reach.ALL, "creates the company and its first administrator before any actor exists"),
    PRINT_WORKER(Reach.ALL, "claims and sends the print jobs of every store"),
    PAYMENT_RECOVERY(Reach.ALL, "sweeps, resolves and counts the payment attempts in doubt of every store"),
    RESERVATIONS(Reach.ALL, "marks the no-shows of every store"),
    STOCK_RECONCILE(Reach.ALL, "reconciles the stock balances of every store"),
    REPORTING_RECOVERY(Reach.ALL, "fails the rebuild runs of every store that a restart interrupted"),
    METRICS(Reach.ALL, "installation-wide gauges, whoever scrapes them (a token scrape has no actor)"),
    ALERTS(Reach.ALL, "collects the operational alerts of every store, then filters them per actor (D149)"),
    EVENT_MAINTENANCE(Reach.NONE, "resubmits, purges and counts event publications (an unscoped table)"),
    IDENTITY_MAINTENANCE(Reach.NONE, "purges login throttles and expires support grants (unscoped tables)"),
    HOUSEKEEPING(Reach.NONE, "purges idempotency records and sends the change-stream heartbeat");

    /** {@code ALL} = {@code app.all_stores on}; {@code NONE} = an explicit empty scope. */
    public enum Reach {
        ALL,
        NONE
    }

    private final Reach reach;
    private final String reason;

    SystemScope(Reach reach, String reason) {
        this.reach = reach;
        this.reason = reason;
    }

    public Reach reach() {
        return reach;
    }

    public String reason() {
        return reason;
    }

    /** The store scope it runs in, with origin {@code system:<NAME>}. */
    public StoreScope scope() {
        String origin = "system:" + name();
        return reach == Reach.ALL ? StoreScope.all(origin) : StoreScope.none(origin);
    }
}
