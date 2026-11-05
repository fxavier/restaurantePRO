package com.xavier.backend.orders;

import com.xavier.backend.orders.service.OrderCoordinationService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What production tells orders (D35): it has received a round. Called from production's idempotent
 * {@code OrderBatchConfirmed} listener, in its transaction, with no session (audited as the system).
 */
@Service
public class OrdersProductionApi {

    private final OrderCoordinationService coordination;

    OrdersProductionApi(OrderCoordinationService coordination) {
        this.coordination = coordination;
    }

    /**
     * CONFIRMED → DISPATCHED. Idempotent: a batch already DISPATCHED is left as it is. It does not change the order's
     * version (a dispatch must not make the POS's next round stale).
     *
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown batch
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markBatchDispatched(UUID batchId) {
        coordination.markBatchDispatched(batchId);
    }
}
