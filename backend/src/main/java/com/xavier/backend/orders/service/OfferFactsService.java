package com.xavier.backend.orders.service;

import com.xavier.backend.orders.OfferFacts;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.repository.OrderRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Reads what a closed order gave away as offers, for reporting (S53), through {@code OrdersApi}, in the caller's
 * read-only transaction; nothing here authorizes (D27). A closed order never changes, so each pull is the same.
 */
@Service
public class OfferFactsService {

    private final OrderLookup lookup;
    private final OrderViews views;
    private final OrderRepository orders;

    OfferFactsService(OrderLookup lookup, OrderViews views, OrderRepository orders) {
        this.lookup = lookup;
        this.views = views;
        this.orders = orders;
    }

    public OfferFacts facts(UUID orderId) {
        Order order = lookup.load(orderId);
        return new OfferFacts(order.getId(), order.getStoreId(), order.getBusinessDay(),
                order.getLifecycle() == OrderLifecycle.CLOSED ? views.offers(order) : List.of());
    }

    public Page<UUID> closedOrderIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return orders.findIdsByStoreAndLifecycleAndBusinessDay(storeId, OrderLifecycle.CLOSED, from, to,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }
}
