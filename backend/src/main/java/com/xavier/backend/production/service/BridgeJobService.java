package com.xavier.backend.production.service;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.production.adapter.SendResult;
import com.xavier.backend.production.exception.ProductionNotFoundException;
import com.xavier.backend.production.model.dto.BridgeJobView;
import com.xavier.backend.production.model.dto.BridgeOutcomeRequest;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The print bridge's side of the queue (D187). The caller is the bridge of the request's {@code BRIDGE} membership,
 * an enrolled, not revoked print bridge of one store; everything it does runs in that store's scope, so row-level
 * security itself keeps it to its own store's (and tenant's) jobs. A claim takes at most one due job with a lease
 * ({@link PrintQueue#claimDue}: {@code FOR UPDATE SKIP LOCKED}, PROCESSING, the bridge recorded as its claimant) and
 * answers at once: one job per claim, so a job's lease starts only when its own send can start (review 1), and no
 * server thread waits for work (review 2: an idle bridge polls). Only the claiming bridge reports the outcome.
 */
@Service
public class BridgeJobService {

    private final PrintQueue queue;
    private final PrintJobRepository jobs;
    private final OrganizationApi organization;
    private final TenantScopes tenants;
    private final StoreScopes stores;

    BridgeJobService(PrintQueue queue, PrintJobRepository jobs, OrganizationApi organization, TenantScopes tenants,
                     StoreScopes stores) {
        this.queue = queue;
        this.jobs = jobs;
        this.organization = organization;
        this.tenants = tenants;
        this.stores = stores;
    }

    /** The calling bridge's next due job, leased to it; empty when none is due. */
    public Optional<BridgeJobView> claim() {
        TerminalSummary bridge = callingBridge();
        return inStore(bridge, () -> queue.claimDue(1, bridge.id())).stream().map(BridgeJobService::view)
                .findFirst();
    }

    /**
     * The send's outcome of a job the bridge claimed. Recorded once, while the job is PROCESSING (a repeat, or a late
     * report after the lease ran out, changes nothing, D50 §7.2).
     *
     * @throws ProductionNotFoundException for a job this bridge does not hold: another store's or tenant's, another
     *                                     bridge's, or one nobody claimed (the same 404)
     */
    public void recordOutcome(UUID jobId, BridgeOutcomeRequest outcome) {
        TerminalSummary bridge = callingBridge();
        inStore(bridge, () -> {
            if (jobs.findById(jobId).filter(job -> job.isClaimedBy(bridge.id())).isEmpty()) {
                throw new ProductionNotFoundException("PrintJob", jobId);
            }
            queue.record(jobId, new SendResult(outcome.outcome(), outcome.paperNearEnd()));
            return null;
        });
    }

    private TerminalSummary callingBridge() {
        return tenants.current().filter(context -> context.kind() == MembershipKind.BRIDGE)
                .map(TenantContext::principalId).flatMap(organization::findTerminal)
                .filter(TerminalSummary::activeBridge)
                .orElseThrow(() -> new AccessDeniedException("Not an enrolled print bridge"));
    }

    private <T> T inStore(TerminalSummary bridge, java.util.function.Supplier<T> action) {
        return stores.callForStore(bridge.storeId(), "bridge:" + bridge.id(), action);
    }

    private static BridgeJobView view(Dispatch dispatch) {
        return new BridgeJobView(dispatch.jobId(), dispatch.address().host(), dispatch.address().port(),
                Base64.getEncoder().encodeToString(dispatch.payload()), dispatch.probeStatus());
    }
}
