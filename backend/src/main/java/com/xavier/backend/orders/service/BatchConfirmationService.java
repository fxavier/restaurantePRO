package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.model.CatalogPricing;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.dto.BatchView;
import com.xavier.backend.orders.model.dto.ConfirmBatchRequest;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms a round (D50 §7.1, spec §8.4) in ONE transaction: the order version the POS showed, the catalog version it
 * priced from ({@code CATALOG_CHANGED}), the snapshot stamped on the drafts at this instant (D58), the batch, and
 * {@link OrderBatchConfirmed} with the round's whole content, whose registry publication is the persisted production
 * work. Two terminals confirming one order: the order row's version update lets one win; the other gets 409 and its
 * drafts stay drafts. {@code UNIQUE(order_id, batch_no)} is the backstop.
 */
@Service
public class BatchConfirmationService {

    static final String BATCH_NO_UK = "order_batch_order_no_uk";

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderBatchRepository batches;
    private final OrderLookup lookup;
    private final CatalogApi catalog;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    BatchConfirmationService(OrderRepository orders, OrderLineRepository lines, OrderBatchRepository batches,
                             OrderLookup lookup, CatalogApi catalog, AccessPolicy accessPolicy,
                             ApplicationEventPublisher events, OrderChanges changes, OrderViews views, Clock clock) {
        this.orders = orders;
        this.lines = lines;
        this.batches = batches;
        this.lookup = lookup;
        this.catalog = catalog;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.changes = changes;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public BatchView confirm(UUID orderId, ConfirmBatchRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), request.orderVersion());
        order.requireChangeable();
        lookup.requireTableUnlocked(order);
        CatalogSnapshot current = catalog.currentSnapshot(order.getStoreId());
        if (current.version() != request.catalogVersion()) {
            throw OrderConflictException.catalogChanged(request.catalogVersion(), current.version());
        }
        List<OrderLine> drafts = drafts(orderId, request.lineIds());
        Instant now = clock.instant();
        Map<OrderLine, LineSnapshot> priced = new LinkedHashMap<>();
        drafts.forEach(line -> priced.put(line,
                CatalogPricing.snapshotOf(current, line.getItemId(), line.getVariantId(), now)));

        int batchNo = order.confirmBatch();
        orders.saveAndFlush(order); // the version check: a concurrent round on this order loses here (409)
        OrderBatch batch = UniqueConstraints.saveAndFlush(batches, OrderBatch.confirmed(orderId, order.getStoreId(),
                        batchNo, pos.terminalId(), pos.employeeId(), current.releaseId(), current.version(), now),
                () -> new ObjectOptimisticLockingFailureException(Order.class, orderId), BATCH_NO_UK);
        priced.forEach((line, snapshot) -> line.confirm(snapshot, batch.getId(), pos.employeeId(), now));
        lines.saveAllAndFlush(drafts);

        events.publishEvent(new OrderBatchConfirmed(orderId, batch.getId(), batchNo, order.getStoreId(),
                pos.terminalId(), order.getOrderNumber(), order.getTableCode(), pos.employeeId(), now,
                priced.entrySet().stream().map(entry -> eventLine(entry.getKey(), entry.getValue())).toList()));
        changes.record("ORDER_BATCH_CONFIRMED", order, Map.of("batchId", batch.getId().toString(),
                "batchNo", batchNo, "lines", drafts.size(), "catalogVersion", current.version()));
        return views.of(order).batches().stream().filter(view -> view.id().equals(batch.getId())).findFirst()
                .orElseThrow();
    }

    /** Every requested id is a DRAFT of this order, else 409 {@code LINE_NOT_DRAFT}; in creation order. */
    private List<OrderLine> drafts(UUID orderId, List<UUID> lineIds) {
        Set<UUID> requested = new LinkedHashSet<>(lineIds);
        List<OrderLine> found = lines.findByOrderIdAndIdIn(orderId, requested);
        for (UUID id : requested) {
            OrderLine line = found.stream().filter(candidate -> candidate.getId().equals(id)).findFirst()
                    .orElseThrow(() -> OrderConflictException.lineNotDraft(id));
            line.requireDraft();
        }
        return found.stream().sorted(Comparator.comparing(OrderLine::getId)).toList();
    }

    private static Line eventLine(OrderLine line, LineSnapshot snapshot) {
        return new Line(line.getId(), snapshot.itemCode(), snapshot.description(), snapshot.variantName(),
                line.getQuantity(), line.getNotes(), snapshot.destination());
    }
}
