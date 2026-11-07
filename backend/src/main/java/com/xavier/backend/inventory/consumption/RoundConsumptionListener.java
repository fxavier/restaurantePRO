package com.xavier.backend.inventory.consumption;

import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * D116: a confirmed round is consumed after it commits, in this listener's own transaction, from the persisted
 * publication (a failure leaves it incomplete and it is delivered again; consumption is idempotent). The sale never
 * waits for it: its backlog is {@code pendingFacts} on the inventory status. The listener id prefix
 * {@code com.xavier.backend.inventory.consumption} is the S37 contract.
 */
@Component
public class RoundConsumptionListener {

    private final RoundConsumption consumption;

    RoundConsumptionListener(RoundConsumption consumption) {
        this.consumption = consumption;
    }

    @ApplicationModuleListener
    public void on(OrderBatchConfirmed event) {
        consumption.consume(event.batchId());
    }
}
