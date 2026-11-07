package com.xavier.backend.reporting.inventory;

import com.xavier.backend.inventory.model.event.ConsumptionWasteRecorded;
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.reporting.service.CostProjections;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Reporting's R4 inputs (D131, the D84 pattern): inventory's and orders' thin events trigger a pull of the immutable
 * facts through their read-only facades, in their own transaction after the owner's commit. A failure stays pending
 * in the registry (counted in {@code pendingFacts}, D86) and is retried; every row is keyed by its source, so a
 * redelivery changes nothing.
 */
@Service
public class CostOfGoodsListeners {

    private final CostProjections projections;

    CostOfGoodsListeners(CostProjections projections) {
        this.projections = projections;
    }

    /** Consumption, reversals and manual waste of the source; a correction's or a return's cost variances. */
    @ApplicationModuleListener
    public void on(StockMovementsPosted event) {
        projections.projectStockSource(event.sourceType(), event.sourceId());
    }

    /** A wasted void moved part of a sale's cost to waste (no stock moved, so no StockMovementsPosted). */
    @ApplicationModuleListener
    public void on(ConsumptionWasteRecorded event) {
        projections.projectWaste(event.voidId());
    }

    @ApplicationModuleListener
    public void on(LineDiscountsRecorded event) {
        projections.projectDiscounts(event.sourceType(), event.sourceId());
    }
}
