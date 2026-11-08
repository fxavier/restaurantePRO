package com.xavier.backend.orders.service;

import com.xavier.backend.orders.model.event.OrderClosed;
import org.springframework.context.ApplicationEventPublisher;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.LoyaltyApi;
import java.time.Instant;
import java.util.Set;
import com.xavier.backend.dining.model.dto.CloseReason;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.delivery.service.DeliveryGuards;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * The work behind {@code OrdersApi} (settlement) and {@code OrdersProductionApi} (production). It runs in the
 * caller's transaction and does not authorize (D27, D36). Each write that changes the order flushes at once, so a
 * concurrent writer that read the old version fails inside the caller's transaction (409, D49).
 */
@Service
public class OrderCoordinationService {

    private final OrderRepository orders;
    private final OrderBatchRepository batches;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final OrderChanges changes;
    private final DiningApi dining;
    private final RewardLineage rewards;
    private final LineFamilies families;
    private final LoyaltyApi loyalty;
    private final ApplicationEventPublisher events;
    private final DeliveryGuards deliveries;
    private final Clock clock;

    OrderCoordinationService(OrderRepository orders, OrderBatchRepository batches, OrderLookup lookup,
                             OrderViews views, OrderChanges changes, DiningApi dining,
                             RewardLineage rewards, LineFamilies families, LoyaltyApi loyalty,
                             ApplicationEventPublisher events, Clock clock) {
        this.events = events;
                             DeliveryGuards deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.rewards = rewards;
        this.families = families;
        this.loyalty = loyalty;
        this.orders = orders;
        this.batches = batches;
        this.lookup = lookup;
        this.views = views;
        this.changes = changes;
        this.dining = dining;
        this.clock = clock;
    }

    public UUID storeOf(UUID orderId) {
        return orders.findStoreIdById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
    }

    public OrderBillingView billingView(UUID orderId) {
        return views.billing(lookup.load(orderId));
    }

    public List<OrderBillingView> openBillingViews(UUID storeId) {
        return views.billing(orders.findByStoreIdAndLifecycleOrderByOpenedAt(storeId, OrderLifecycle.OPEN));
    }

    public void lockForPayment(UUID orderId) {
        change(orderId, "ORDER_PAYMENT_LOCKED", Order::lockForPayment);
    }

    public void releasePaymentLock(UUID orderId) {
        change(orderId, "ORDER_PAYMENT_LOCK_RELEASED", Order::releasePaymentLock);
    }

    public void freezeForInvoice(UUID orderId) {
        change(orderId, "ORDER_FROZEN_FOR_INVOICE", Order::freezeForInvoice);
    }

    public void close(UUID orderId, long expectedVersion, UUID actorEmployeeId) {
        Order order = lookup.load(orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), expectedVersion);
        deliveries.requireClosable(order);
        Instant now = clock.instant();
        order.close(actorEmployeeId, now);
        orders.saveAndFlush(order);
        earn(order, now);
        if (order.getTableId() != null) {
            dining.closeOccupation(orderId, CloseReason.ORDER_CLOSED);
        }
        changes.record("ORDER_CLOSED", order, Map.of("closedByEmployeeId", actorEmployeeId.toString()));
        events.publishEvent(new OrderClosed(order.getId(), order.getStoreId(), order.getBusinessDay()));
    }

    /**
     * D111: a member customer earns in the close's transaction, on each billing line's effective gross; a line whose
     * reward is its own or an ancestor's (D112a) is flagged and never earns. Insert-only in promotions: no FOR UPDATE,
     * only the FOR KEY SHARE its foreign key takes on {@code loyalty_account} (D132); idempotent per order.
     */
    private void earn(Order order, Instant now) {
        // a performance guard, not a rule: earnForOrder ignores non-members, but this skips reading the catalog
        // releases (family) and the reward lineage while the close holds its locks
        if (order.getCustomerId() == null || !loyalty.isMember(order.getCustomerId())) {
            return;
        }
        List<BillingLine> billed = views.billing(order).lines();
        if (billed.isEmpty()) {
            return;
        }
        Set<UUID> rewarded = rewards.rewarded(billed.stream().map(BillingLine::lineId).toList());
        LineFamilies.Resolver familyOf = families.resolver(order.getStoreId());
        List<EarnLine> lines = billed.stream().map(line -> new EarnLine(line.itemId(),
                familyOf.familyOf(line.releaseId(), line.itemId()), line.gross(), rewarded.contains(line.lineId())))
                .toList();
        loyalty.earnForOrder(new EarnCommand(order.getId(), order.getStoreId(), order.getCustomerId(),
                order.getBusinessDay(), now, lines));
    }

    public long countOpen(UUID storeId, LocalDate businessDayInclusive) {
        return orders.countByStoreIdAndLifecycleAndBusinessDayLessThanEqual(storeId, OrderLifecycle.OPEN,
                businessDayInclusive);
    }

    /**
     * CONFIRMED → DISPATCHED by a conditional update: a repeated or concurrent notice (production's listener may be
     * redelivered) changes nothing and is not audited again.
     */
    public void markBatchDispatched(UUID batchId) {
        OrderBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new OrderNotFoundException("OrderBatch", batchId));
        if (batches.markDispatched(batchId, clock.instant()) == 0) {
            return;
        }
        changes.record("ORDER_BATCH_DISPATCHED", "OrderBatch", batchId, batch.getStoreId(), batch.getOrderId(),
                Map.of("orderId", batch.getOrderId().toString(), "batchNo", batch.getBatchNo()));
    }

    /** Applies {@code transition}; when it changed the order, the version moves now and the change is audited. */
    private void change(UUID orderId, String action, Predicate<Order> transition) {
        Order order = lookup.load(orderId);
        if (!transition.test(order)) {
            return;
        }
        orders.saveAndFlush(order);
        changes.record(action, order, Map.of("billingLock", order.getBillingLock().name()));
    }
}
