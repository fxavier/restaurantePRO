package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.dto.OrderSummary;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** POS reads of the session store's orders (ORDER_VIEW; D42). */
@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderLookup lookup;
    private final AccessPolicy accessPolicy;
    private final OrderViews views;

    OrderQueryService(OrderRepository orders, OrderLookup lookup, AccessPolicy accessPolicy, OrderViews views) {
        this.orders = orders;
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
}
