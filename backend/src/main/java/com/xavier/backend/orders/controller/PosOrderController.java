package com.xavier.backend.orders.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.orders.model.dto.OpenOrderRequest;
import com.xavier.backend.orders.model.dto.OpenTableRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.service.OrderOpeningService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** POS order opening (PRD S13). Store and terminal come from the session (D39). */
@RestController
class PosOrderController {

    static final String ORDERS = "/api/v1/pos/orders";

    private final OrderOpeningService opening;
    private final Idempotent idempotent;

    PosOrderController(OrderOpeningService opening, IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.opening = opening;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @PostMapping("/api/v1/pos/tables/{tableId}/open")
    ResponseEntity<OrderView> openTable(@PathVariable UUID tableId, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody OpenTableRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.table.open", key, request, body, OrderView.class,
                () -> created(opening.openTable(tableId, body)));
    }

    @PostMapping(ORDERS)
    ResponseEntity<OrderView> open(@RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody OpenOrderRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.order.open", key, request, body, OrderView.class,
                () -> created(opening.openWalkIn(body)));
    }

    private static ResponseEntity<OrderView> created(OrderView order) {
        return ResponseEntity.created(URI.create(ORDERS + "/" + order.id())).body(order);
    }
}
