package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OrderSummary;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.model.dto.TableActivityView;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** POS reads of the session store's orders (ORDER_VIEW; D42). */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    /**
     * How long a seated table may go without a round before the POS map shows it as overdue ("N min sem ronda"). No
     * store setting exists for it yet; the POS takes it from the response, never from its own code.
     */
    static final Duration ROUND_OVERDUE_AFTER = Duration.ofMinutes(30);

    private final OrderRepository orders;
    private final OrderBatchRepository batches;
    private final OrderLookup lookup;
    private final AccessPolicy accessPolicy;
    private final OrderViews views;

    OrderQueryService(OrderRepository orders, OrderBatchRepository batches, OrderLookup lookup,
                      AccessPolicy accessPolicy, OrderViews views) {
        this.orders = orders;
        this.batches = batches;
        this.lookup = lookup;
        this.accessPolicy = accessPolicy;
        this.views = views;
    }

    public PageResponse<OrderSummary> list(OrderLifecycle lifecycle, Pageable pageable) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        return PageResponse.from(orders.findByStoreIdAndLifecycle(pos.storeId(), lifecycle, pageable)
                .map(Order::toSummary));
    }

    public OrderView get(UUID orderId) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        return views.of(lookup.inSessionStore(pos, orderId));
    }

    /**
     * The session store's seated OPEN orders with their totals and last rounds, for the table map: one read of the
     * orders, one of their lines (the billing views) and one of their rounds, whatever the number of tables.
     */
    public TableActivityView tableActivity() {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        List<Order> seated = orders.findByStoreIdAndLifecycleOrderByOpenedAt(pos.storeId(), OrderLifecycle.OPEN)
                .stream().filter(order -> order.getTableId() != null).toList();
        Map<UUID, Instant> lastRounds = seated.isEmpty() ? Map.of()
                : batches.lastRounds(seated.stream().map(Order::getId).toList()).stream().collect(Collectors.toMap(
                        OrderBatchRepository.LastRound::getOrderId, OrderBatchRepository.LastRound::getLastRoundAt));
        List<TableActivityView.Table> tables = views.billing(seated).stream()
                .map(order -> table(order, lastRounds.get(order.orderId()))).toList();
        return new TableActivityView((int) ROUND_OVERDUE_AFTER.toMinutes(), tables);
    }

    private static TableActivityView.Table table(OrderBillingView order, Instant lastRoundAt) {
        return new TableActivityView.Table(order.tableId(), order.orderId(), order.due(), lastRoundAt);
    }
}
