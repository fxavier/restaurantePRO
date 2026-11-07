package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.ItemKey;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.LineKind;
import com.xavier.backend.orders.model.LinePricing;
import com.xavier.backend.orders.model.MenuSelection;
import com.xavier.backend.orders.model.OrderLineComponent;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.orders.repository.OrderLineComponentRepository;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.stream.Collectors;
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
 *
 * <p>R4 (D101–D104, D108, D109): the promotion release the POS previewed ({@code PROMOTIONS_CHANGED}), no sold-out
 * item ({@code ITEM_SOLD_OUT}), each draft's list price turned into its effective price by promotions at this instant
 * (and the gross the POS showed, {@code PRICES_CHANGED}), a menu's choices checked ({@code MENU_SELECTION_INVALID})
 * and frozen. A round with a promoted line also publishes {@link LineDiscountsRecorded}.
 */
@Service
public class BatchConfirmationService {

    static final String BATCH_NO_UK = "order_batch_order_no_uk";

    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderBatchRepository batches;
    private final OrderLineComponentRepository components;
    private final OrderLookup lookup;
    private final CatalogApi catalog;
    private final LinePricer pricer;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    BatchConfirmationService(OrderRepository orders, OrderLineRepository lines, OrderBatchRepository batches,
                             OrderLineComponentRepository components, OrderLookup lookup, CatalogApi catalog,
                             LinePricer pricer, AccessPolicy accessPolicy, ApplicationEventPublisher events,
                             OrderChanges changes, OrderViews views, Clock clock) {
        this.orders = orders;
        this.lines = lines;
        this.batches = batches;
        this.components = components;
        this.lookup = lookup;
        this.catalog = catalog;
        this.pricer = pricer;
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
        Long promotionVersion = pricer.currentVersion(order.getStoreId());
        if (!Objects.equals(promotionVersion, request.promotionVersion())) {
            throw OrderConflictException.promotionsChanged(request.promotionVersion(), promotionVersion);
        }
        List<OrderLine> drafts = drafts(orderId, request.lineIds());
        Map<UUID, List<OrderLineComponent>> chosen = components.findByLineIdInOrderByLineIdAscPositionAsc(
                drafts.stream().map(OrderLine::getId).toList()).stream()
                .collect(Collectors.groupingBy(OrderLineComponent::getLineId));
        requireAvailable(order, drafts, chosen);
        Instant now = clock.instant();
        LinePricer.Priced pricing = pricer.price(order, current, drafts, now);
        Map<OrderLine, Confirmed> priced = new LinkedHashMap<>();
        for (OrderLine line : drafts) {
            List<OrderLineComponent> lineComponents = chosen.getOrDefault(line.getId(), List.of());
            CatalogSnapshot.Item item = CatalogPricing.itemOf(current, line.getItemId(), line.getVariantId());
            priced.put(line, new Confirmed(CatalogPricing.snapshotOf(current, line.getItemId(), line.getVariantId(),
                    now), pricing.of(line.getId()).orElseThrow(), LineKind.valueOf(item.kind().name()),
                    lineComponents, MenuSelection.validate(current, item, lineComponents.stream()
                    .map(component -> new MenuSelection.Choice(component.getGroupCode(), component.getItemId(),
                            component.getVariantId())).toList())));
        }
        requireExpectedGross(request.expectedGross(), priced);

        int batchNo = order.confirmBatch();
        orders.saveAndFlush(order); // the version check: a concurrent round on this order loses here (409)
        OrderBatch batch = UniqueConstraints.saveAndFlush(batches, OrderBatch.confirmed(orderId, order.getStoreId(),
                        batchNo, pos.terminalId(), pos.employeeId(), current.releaseId(), current.version(), now),
                () -> new ObjectOptimisticLockingFailureException(Order.class, orderId), BATCH_NO_UK);
        priced.values().forEach(Confirmed::freezeComponents);
        components.saveAllAndFlush(priced.values().stream().flatMap(c -> c.components().stream()).toList());
        priced.forEach((line, confirmed) -> line.confirm(confirmed.snapshot(), confirmed.pricing(), confirmed.kind(),
                batch.getId(), pos.employeeId(), now));
        lines.saveAllAndFlush(drafts);

        events.publishEvent(new OrderBatchConfirmed(orderId, batch.getId(), batchNo, order.getStoreId(),
                pos.terminalId(), order.getOrderNumber(), order.getTableCode(), pos.employeeId(), now,
                priced.entrySet().stream().map(entry -> eventLine(entry.getKey(), entry.getValue())).toList(),
                order.getMode()));
        if (priced.values().stream().anyMatch(confirmed -> confirmed.pricing().promotion() != null)) {
            events.publishEvent(new LineDiscountsRecorded(orderId, order.getStoreId(), LineDiscountsRecorded.BATCH,
                    batch.getId()));
        }
        Map<String, Object> details = new HashMap<>(Map.of("batchId", batch.getId().toString(),
                "batchNo", batchNo, "lines", drafts.size(), "catalogVersion", current.version()));
        if (promotionVersion != null) {
            details.put("promotionVersion", promotionVersion);
        }
        changes.record("ORDER_BATCH_CONFIRMED", order, details);
        return views.of(order).batches().stream().filter(view -> view.id().equals(batch.getId())).findFirst()
                .orElseThrow();
    }

    /** One draft as it is confirmed: its snapshot, price, kind and (for a menu) its checked components. */
    private record Confirmed(LineSnapshot snapshot, LinePricing pricing, LineKind kind,
                             List<OrderLineComponent> components, List<MenuSelection.Frozen> frozen) {

        void freezeComponents() {
            for (int i = 0; i < components.size(); i++) {
                MenuSelection.Frozen checked = frozen.get(i);
                components.get(i).freeze(checked.itemCode(), checked.description(), checked.quantity(),
                        checked.destination());
            }
        }
    }

    /** D109: a sold-out item, variant or menu component refuses the round (an item-level flag covers variants). */
    private void requireAvailable(Order order, List<OrderLine> drafts, Map<UUID, List<OrderLineComponent>> chosen) {
        Set<ItemKey> soldOut = catalog.soldOut(order.getStoreId());
        if (soldOut.isEmpty()) {
            return;
        }
        for (OrderLine line : drafts) {
            requireNotSoldOut(soldOut, line.getItemId(), line.getVariantId());
            chosen.getOrDefault(line.getId(), List.of())
                    .forEach(component -> requireNotSoldOut(soldOut, component.getItemId(), component.getVariantId()));
        }
    }

    private static void requireNotSoldOut(Set<ItemKey> soldOut, UUID itemId, UUID variantId) {
        if (soldOut.contains(new ItemKey(itemId, null)) || variantId != null
                && soldOut.contains(new ItemKey(itemId, variantId))) {
            throw OrderRuleException.itemSoldOut(itemId, variantId);
        }
    }

    /** D104: the gross the POS showed must still be the gross now (a time window may have passed). */
    private static void requireExpectedGross(BigDecimal expected, Map<OrderLine, Confirmed> priced) {
        if (expected == null) {
            return;
        }
        BigDecimal gross = BigDecimal.ZERO;
        List<Map<String, Object>> preview = new ArrayList<>();
        for (Map.Entry<OrderLine, Confirmed> entry : priced.entrySet()) {
            LinePricing pricing = entry.getValue().pricing();
            Money lineGross = LineAmounts.of(pricing.unitPrice(), entry.getKey().getQuantity(),
                    entry.getValue().snapshot().taxRatePercent()).gross();
            gross = gross.add(lineGross.amount());
            preview.add(Map.of("lineId", entry.getKey().getId().toString(),
                    "listUnitPrice", pricing.listUnitPrice().amount(), "unitPrice", pricing.unitPrice().amount(),
                    "gross", lineGross.amount()));
        }
        if (gross.compareTo(expected) != 0) {
            throw OrderConflictException.pricesChanged(expected, gross, preview);
        }
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

    private static Line eventLine(OrderLine line, Confirmed confirmed) {
        LineSnapshot snapshot = confirmed.snapshot();
        return new Line(line.getId(), snapshot.itemCode(), snapshot.description(), snapshot.variantName(),
                line.getQuantity(), line.getNotes(), snapshot.destination(), confirmed.components().stream()
                .map(component -> new ComponentLine(component.getItemCode(), component.getDescription(),
                        component.getQuantity().multiply(line.getQuantity()).setScale(3), component.getDestination()))
                .toList());
    }
}
