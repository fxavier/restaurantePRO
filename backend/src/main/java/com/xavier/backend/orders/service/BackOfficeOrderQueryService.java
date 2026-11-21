package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OrderRow;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.BusinessDateRange;
import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BackOffice reads of a store's orders: the search and the detail (ORDER_VIEW in that store; D42). */
@Service
@Transactional(readOnly = true)
public class BackOfficeOrderQueryService {

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final StoreProbe probe;
    private final AccessPolicy accessPolicy;

    BackOfficeOrderQueryService(OrderRepository orders, OrderLookup lookup, OrderViews views, StoreProbe probe,
                                AccessPolicy accessPolicy) {
        this.orders = orders;
        this.lookup = lookup;
        this.views = views;
        this.probe = probe;
        this.accessPolicy = accessPolicy;
    }

    /**
     * The store's orders of business days in {@code [from, to]}, optionally of one lifecycle and mode. {@code q}
     * matches the order number exactly, or a table code containing it (case-insensitive).
     */
    public PageResponse<OrderRow> search(UUID storeId, LocalDate from, LocalDate to, OrderLifecycle lifecycle,
                                         OrderMode mode, String q, Pageable pageable) {
        accessPolicy.require(Permission.ORDER_VIEW, storeId);
        BusinessDateRange days = BusinessDateRange.of(from, to);
        String text = q == null || q.isBlank() ? null : q.strip();
        Page<Order> page = orders.search(storeId, days.from(), days.to(), lifecycle, mode, orderNumber(text),
                text == null ? null : "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%", pageable);
        // one read of lines and adjustments for the whole page, not one per order
        Map<UUID, OrderBillingView> billing = views.billing(page.getContent()).stream()
                .collect(Collectors.toMap(OrderBillingView::orderId, Function.identity()));
        return PageResponse.from(page.map(order -> new OrderRow(order.getId(), order.getStoreId(),
                order.getOrderNumber(), order.getMode(), order.getTableCode(), order.getGuests(),
                order.getCustomerId(), order.getLifecycle(), order.getBusinessDay(), order.getOpenedAt(),
                order.getClosedAt(), billing.get(order.getId()).due())));
    }

    /** D42 (b): the order's store only (404), ORDER_VIEW in it (403), then the order with its lines. */
    public OrderView get(UUID orderId) {
        UUID storeId = probe.storeOf(OrderRepository.TABLE, orderId)
                .orElseThrow(() -> new OrderNotFoundException(OrderChanges.ENTITY, orderId));
        accessPolicy.require(Permission.ORDER_VIEW, storeId);
        return views.of(lookup.load(orderId));
    }

    private static Long orderNumber(String text) {
        if (text == null || !text.chars().allMatch(Character::isDigit) || text.length() > 18) {
            return null;
        }
        return Long.valueOf(text);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
