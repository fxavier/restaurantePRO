package com.xavier.backend.inventory.consumption;

import com.xavier.backend.inventory.consumption.service.VoidReversal;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** D117: a void reverses the consumption of the round line it descends from, in this listener's own transaction. */
@Component
public class VoidConsumptionListener {

    private final VoidReversal reversal;

    VoidConsumptionListener(VoidReversal reversal) {
        this.reversal = reversal;
    }

    @ApplicationModuleListener
    public void on(OrderLinesVoided event) {
        reversal.reverse(event.voidId());
    }
}
