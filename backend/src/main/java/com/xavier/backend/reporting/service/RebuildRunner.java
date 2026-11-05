package com.xavier.backend.reporting.service;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.reporting.model.dto.RebuildView;
import com.xavier.backend.reporting.repository.RebuildRuns;
import com.xavier.backend.settlement.SettlementFacts;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Runs one rebuild (D84): deletes the store's projection rows in the range, then pages each owner's id listing and
 * projects every fact through {@link Projections}, the live listeners' code path. Each fact commits on its own, so a
 * live event that lands meanwhile is simply a duplicate. Progress is the number of facts pulled so far.
 */
@Service
class RebuildRunner {

    private static final Logger log = LoggerFactory.getLogger(RebuildRunner.class);
    static final int PAGE = 200;

    private final RebuildRuns runs;
    private final Projections projections;
    private final FiscalApi fiscal;
    private final OrdersApi orders;
    private final SettlementFacts settlement;
    private final Clock clock;

    RebuildRunner(RebuildRuns runs, Projections projections, FiscalApi fiscal, OrdersApi orders,
                  SettlementFacts settlement, Clock clock) {
        this.runs = runs;
        this.projections = projections;
        this.fiscal = fiscal;
        this.orders = orders;
        this.settlement = settlement;
        this.clock = clock;
    }

    void run(UUID runId) {
        RebuildView run = runs.find(runId).orElseThrow();
        long[] projected = {0};
        try {
            projections.clear(run.storeId(), run.from(), run.to());
            pull(runId, projected, page -> fiscal.issuedDocumentIds(run.storeId(), run.from(), run.to(), page),
                    projections::projectDocument);
            pull(runId, projected, page -> orders.voidIds(run.storeId(), run.from(), run.to(), page),
                    projections::projectVoid);
            pull(runId, projected, page -> settlement.paymentIds(run.storeId(), run.from(), run.to(), page),
                    projections::projectPayment);
            pull(runId, projected, page -> settlement.refundIds(run.storeId(), run.from(), run.to(), page),
                    projections::projectRefund);
            pull(runId, projected, page -> settlement.creditChargeIds(run.storeId(), run.from(), run.to(), page),
                    projections::projectCreditCharge);
            runs.finish(runId, projected[0], null, clock.instant());
        } catch (RuntimeException e) {
            log.error("Rebuild {} of store {} failed after {} facts", runId, run.storeId(), projected[0], e);
            // the type only: the details (which may name internals) are in the server log
            runs.finish(runId, projected[0], "Failed: " + e.getClass().getSimpleName(), clock.instant());
        }
    }

    private void pull(UUID runId, long[] projected, Function<Pageable, Page<UUID>> ids, Consumer<UUID> project) {
        Page<UUID> page;
        int number = 0;
        do {
            page = ids.apply(PageRequest.of(number++, PAGE));
            page.forEach(project);
            projected[0] += page.getNumberOfElements();
            runs.progress(runId, projected[0]);
        } while (page.hasNext());
    }
}
