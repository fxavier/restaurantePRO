package com.xavier.backend.production.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.production.model.KitchenStatus;
import com.xavier.backend.production.model.dto.ProductionAlertsView;
import com.xavier.backend.production.repository.KitchenProgressRepository;
import com.xavier.backend.production.repository.PrintJobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The POS alerts of production for the session's store, with PRODUCTION_VIEW (who may read the print queue and the
 * kitchen display): two indexed counts. The settlement alert is its own read; the POS composes them (no module here
 * may depend on both).
 */
@Service
public class ProductionAlertsService {

    /** A round still to be made: READY is cooked and only waits to be served. */
    private static final Set<KitchenStatus> NOT_READY = Set.of(KitchenStatus.QUEUED, KitchenStatus.IN_PREPARATION);

    private final PrintJobRepository jobs;
    private final KitchenProgressRepository kitchen;
    private final AccessPolicy accessPolicy;
    private final Clock clock;
    private final Duration kitchenDelay;

    ProductionAlertsService(PrintJobRepository jobs, KitchenProgressRepository kitchen, AccessPolicy accessPolicy,
                            Clock clock,
                            @Value("${restaurantpro.production.kitchen-delay:PT15M}") Duration kitchenDelay) {
        this.jobs = jobs;
        this.kitchen = kitchen;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
        this.kitchenDelay = kitchenDelay;
    }

    @Transactional(readOnly = true)
    public ProductionAlertsView alertsForPos() {
        PosContext pos = accessPolicy.requirePos(Permission.PRODUCTION_VIEW);
        Instant now = clock.instant();
        return new ProductionAlertsView(
                jobs.countPendingInStore(pos.storeId(), now.minus(ProductionMetrics.ATTENTION_WINDOW)),
                kitchen.countByStoreIdAndStatusInAndQueuedAtBefore(pos.storeId(), NOT_READY, now.minus(kitchenDelay)),
                kitchenDelay.toMinutes());
    }
}
