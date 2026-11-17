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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * The print bridge's side of the queue (D187). The caller is the bridge of the request's {@code BRIDGE} membership,
 * an enrolled, not revoked print bridge of one store; everything it does runs in that store's scope, so row-level
 * security itself keeps it to its own store's (and tenant's) jobs. A claim takes the store's due jobs with a lease
 * ({@link PrintQueue#claimDue}: {@code FOR UPDATE SKIP LOCKED}, one per printer, PROCESSING) and, when there are none,
 * waits for up to {@code wait}, looking again every {@code poll} (a long poll: the bridge's connection is outbound
 * only). No transaction is held while waiting.
 */
@Service
public class BridgeJobService {

    static final Duration MAX_WAIT = Duration.ofSeconds(30);
    static final int MAX_CLAIM = 10;

    private final PrintQueue queue;
    private final PrintJobRepository jobs;
    private final OrganizationApi organization;
    private final TenantScopes tenants;
    private final StoreScopes stores;
    private final Clock clock;
    private final Duration poll;

    BridgeJobService(PrintQueue queue, PrintJobRepository jobs, OrganizationApi organization, TenantScopes tenants,
                     StoreScopes stores, Clock clock,
                     @Value("${restaurantpro.production.bridge.poll:PT1S}") Duration poll) {
        this.queue = queue;
        this.jobs = jobs;
        this.organization = organization;
        this.tenants = tenants;
        this.stores = stores;
        this.clock = clock;
        this.poll = poll;
    }

    /** The calling bridge's due jobs, at most {@code limit}, waiting up to {@code wait} for one. */
    public List<BridgeJobView> claim(int limit, Duration wait) throws InterruptedException {
        TerminalSummary bridge = callingBridge();
        int count = Math.clamp(limit, 1, MAX_CLAIM);
        Instant deadline = clock.instant().plus(wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait);
        while (true) {
            List<BridgeJobView> claimed = inStore(bridge, () -> queue.claimDue(count)).stream()
                    .map(BridgeJobService::view).toList();
            Duration left = Duration.between(clock.instant(), deadline);
            if (!claimed.isEmpty() || left.isNegative() || left.isZero()) {
                return claimed;
            }
            Thread.sleep(left.compareTo(poll) < 0 ? left : poll);
        }
    }

    /**
     * The send's outcome of a job the bridge claimed. Recorded once, while the job is PROCESSING (a repeat, a late
     * report after the lease ran out, or one for a job never claimed changes nothing, D50 §7.2).
     *
     * @throws ProductionNotFoundException for a job the bridge cannot see (another store's or tenant's: the same 404)
     */
    public void recordOutcome(UUID jobId, BridgeOutcomeRequest outcome) {
        TerminalSummary bridge = callingBridge();
        inStore(bridge, () -> {
            if (!jobs.existsById(jobId)) {
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
