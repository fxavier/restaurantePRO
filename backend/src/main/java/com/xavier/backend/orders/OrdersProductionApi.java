package com.xavier.backend.orders;

import com.xavier.backend.orders.delivery.service.DeliverySlips;
import com.xavier.backend.orders.service.OrderCoordinationService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What production tells orders (D35): it has received a round. Called from production's idempotent
 * {@code OrderBatchConfirmed} listener, in its transaction, with no session (audited as the system). R4: what a
 * delivery slip prints (D129).
 */
@Service
public class OrdersProductionApi {

    private final OrderCoordinationService coordination;
    private final DeliverySlips slips;

    OrdersProductionApi(OrderCoordinationService coordination, DeliverySlips slips) {
        this.coordination = coordination;
        this.slips = slips;
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

    /**
     * The non-fiscal slip of a DELIVERY order (D129), for the {@code DeliveryDispatched} listener: the customer's name
     * read now, the delivery's phone and address snapshot, its zone and promise, the billable lines and the due.
     *
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) when the order has no delivery
     */
    @Transactional(readOnly = true)
    public DeliverySlip deliverySlip(UUID orderId) {
        return slips.of(orderId);
    }
}
