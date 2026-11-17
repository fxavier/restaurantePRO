package com.xavier.backend.access;

/**
 * The reviewed allow-list of every actor-less store scope ("âmbito de sistema", D166): jobs, the bootstrap, the
 * readiness hook, DB-backed gauges and the alert aggregation. A new constant, or a new reach of ALL, is a visible diff
 * in review ({@code SystemScopeRegistryTest}). Applied with {@link SystemScoped} or {@link StoreScopes#run}.
 *
 * <p>Every system scope is inside one tenant (D185 as amended): there is no cross-tenant scope for the application
 * role. A {@link SystemScoped} method of reach {@code ALL} runs once per ACTIVE tenant (lease, own binding, one
 * tenant's failure counted and not stopping the others), each time in every store of that tenant; one of reach
 * {@code NONE} runs once with no tenant and no store (PLATFORM tables only). {@link #BOOTSTRAP} runs in the bootstrap
 * tenant only (the pre-SaaS environment bootstrap, D30, removed by S76). A listener runs in its event's tenant.
 */
public enum SystemScope {
    BOOTSTRAP(Reach.ALL, "creates the bootstrap tenant's company and first administrator before any actor exists"),
    PROVISIONING(Reach.ALL, "creates a new tenant's company profile and first store from TenantProvisioned (D181)"),
    PRINT_WORKER(Reach.ALL, "expires the lost print-job leases of every store (the bridges send, D187)"),
    DEVICE_ENROLMENT(Reach.ALL, "redeems a device's one-time code in the tenant its pending membership names, before "
            + "the device has a token (D182)"),
    PAYMENT_RECOVERY(Reach.ALL, "sweeps, resolves and counts the payment attempts in doubt of every store"),
    RESERVATIONS(Reach.ALL, "marks the no-shows of every store"),
    STOCK_RECONCILE(Reach.ALL, "reconciles the stock balances of every store"),
    REPORTING_RECOVERY(Reach.ALL, "fails the rebuild runs of every store that a restart interrupted"),
    METRICS(Reach.ALL, "installation-wide gauges, whoever scrapes them (a token scrape has no actor)"),
    ALERTS(Reach.ALL, "collects the operational alerts of every store, then filters them per actor (D149)"),
    EVENT_MAINTENANCE(Reach.NONE, "resubmits, purges and counts event publications (an unscoped table)"),
    IDEMPOTENCY_RETENTION(Reach.ALL, "purges each tenant's expired idempotency records (a TENANT table, D186)"),
    PLATFORM(Reach.NONE, "serves a platform operator's /platform/** request: no tenant, PLATFORM tables only (D181)"),
    HOUSEKEEPING(Reach.NONE, "sends the change-stream heartbeat (in memory)");

    /**
     * {@code ALL} = {@code app.all_stores on}, in each ACTIVE tenant in turn; {@code NONE} = an explicit empty scope,
     * no tenant.
     */
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
