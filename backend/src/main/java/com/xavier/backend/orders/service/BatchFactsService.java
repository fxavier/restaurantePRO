package com.xavier.backend.orders.service;

import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads round facts for inventory (D100) through {@code OrdersApi}, in the caller's read-only transaction. Rounds and
 * their lines are immutable once confirmed, so each pull returns the same answer; nothing here authorizes (D27).
 */
@Service
public class BatchFactsService {

    static final String ENTITY = "OrderBatch";

    private final OrderBatchRepository batches;
    private final OrderLineRepository lines;
    private final OrderLookup lookup;

    BatchFactsService(OrderBatchRepository batches, OrderLineRepository lines, OrderLookup lookup) {
        this.batches = batches;
        this.lines = lines;
        this.lookup = lookup;
    }

    public BatchFacts facts(UUID batchId) {
        OrderBatch batch = batches.findById(batchId).orElseThrow(() -> new OrderNotFoundException(ENTITY, batchId));
        Order order = lookup.load(batch.getOrderId());
        return new BatchFacts(batch.getId(), order.getId(), batch.getStoreId(), order.getBusinessDay(),
                order.getMode(), batch.getConfirmedAt(),
                lines.findByBatchIdOrderById(batchId).stream().map(BatchFactsService::fact).toList());
    }

    private static BatchFacts.BatchLineFact fact(OrderLine line) {
        LineSnapshot snapshot = line.snapshot().orElseThrow();
        return new BatchFacts.BatchLineFact(line.getId(), line.getItemId(), line.getVariantId(),
                snapshot.releaseId(), line.getQuantity(), snapshot.destination(), List.of());
    }
}
