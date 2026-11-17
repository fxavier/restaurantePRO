package com.xavier.backend.production.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The print dispatcher (D187, D50 §7.2, D46). The central backend cannot reach a restaurant's LAN, so it never sends a
 * job: each store's print bridge claims its jobs ({@link BridgeJobService}) and reports how each send ended. This job,
 * per tenant, only turns the claims whose lease ran out (a bridge that died during a send) into UNKNOWN, for a person
 * to acknowledge or retry (spec §4).
 */
@Service
public class PrintWorker {

    private final PrintQueue queue;

    PrintWorker(PrintQueue queue) {
        this.queue = queue;
    }

    /** One run; also what tests call. @return how many leases expired */
    @SystemScoped(SystemScope.PRINT_WORKER)
    @Scheduled(fixedDelayString = "${restaurantpro.production.worker.poll:PT1S}")
    public int poll() {
        return queue.expireLeases();
    }
}
