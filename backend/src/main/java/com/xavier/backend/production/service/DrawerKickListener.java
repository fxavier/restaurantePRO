package com.xavier.backend.production.service;

import com.xavier.backend.settlement.model.event.CashDrawerKickRequested;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Opens the drawer of a cash payment or refund (D158) after settlement's commit. Idempotent through the ticket key:
 * a redelivered event kicks no second time.
 */
@Service
public class DrawerKickListener {

    private final DrawerKickService kicks;

    DrawerKickListener(DrawerKickService kicks) {
        this.kicks = kicks;
    }

    @ApplicationModuleListener
    public void on(CashDrawerKickRequested event) {
        kicks.kick(event.sourceType(), event.sourceId(), event.storeId(), event.terminalId());
    }
}
