package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.dining.model.dto.OccupationView;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OpenOrderRequest;
import com.xavier.backend.orders.model.dto.OpenTableRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens orders (P15, A1). A DINE_IN order and its table occupation are created in one transaction: the order's
 * partial unique index and dining's occupation index each let one of two concurrent openings win (409
 * {@code TABLE_OCCUPIED}); dining refuses inactive, locked and other-store tables (422) and the order rolls back with
 * it. The business day is fixed at opening (D16).
 */
@Service
public class OrderOpeningService {

    static final String OPEN_TABLE_UK = "order_header_open_table_uk";

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final DiningApi dining;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    OrderOpeningService(OrderRepository orders, OrderLookup lookup, DiningApi dining, OrganizationApi organization,
                        AccessPolicy accessPolicy, OrderChanges changes, OrderViews views, Clock clock) {
        this.orders = orders;
        this.lookup = lookup;
        this.dining = dining;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public OrderView openTable(UUID tableId, OpenTableRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        lookup.requireSeatable(tableId, pos);
        StoreSummary store = store(pos);
        Instant now = clock.instant();
        Order order = Order.dineIn(store.id(), pos.terminalId(), orders.nextOrderNumber(), tableId,
                request.guests(), BusinessDays.of(now, store.timezone(), store.businessDayCutoff()), store.currency(),
                pos.employeeId(), now);
        UniqueConstraints.saveAndFlush(orders, order, () -> OrderConflictException.tableOccupied(tableId),
                OPEN_TABLE_UK);
        OccupationView occupation = dining.openOccupation(tableId, order.getId(), request.guests(), pos.employeeId());
        order.seatedAt(tableId, occupation.tableCode());
        orders.saveAndFlush(order);
        opened(order);
        return views.of(order);
    }

    @Transactional
    public OrderView openWalkIn(OpenOrderRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        StoreSummary store = store(pos);
        Instant now = clock.instant();
        Order order = orders.saveAndFlush(Order.walkIn(request.mode(), store.id(), pos.terminalId(),
                orders.nextOrderNumber(), request.guests(),
                BusinessDays.of(now, store.timezone(), store.businessDayCutoff()), store.currency(),
                pos.employeeId(), now));
        opened(order);
        return views.of(order);
    }

    private void opened(Order order) {
        Map<String, Object> details = new HashMap<>();
        details.put("mode", order.getMode().name());
        details.put("orderNumber", order.getOrderNumber());
        if (order.getTableId() != null) {
            details.put("tableId", order.getTableId().toString());
        }
        changes.record("ORDER_OPENED", order, details);
    }

    private StoreSummary store(PosContext pos) {
        return organization.findStore(pos.storeId())
                .orElseThrow(() -> new IllegalStateException("requirePos let an unknown store through: " + pos));
    }
}
