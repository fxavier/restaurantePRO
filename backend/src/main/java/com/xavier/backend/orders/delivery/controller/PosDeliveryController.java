package com.xavier.backend.orders.delivery.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.orders.DeliveryStatus;
import com.xavier.backend.orders.delivery.model.dto.DeliveredRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryUpdateRequest;
import com.xavier.backend.orders.delivery.model.dto.DeliveryView;
import com.xavier.backend.orders.delivery.model.dto.DispatchRequest;
import com.xavier.backend.orders.delivery.model.dto.ReturnedRequest;
import com.xavier.backend.orders.delivery.model.dto.ZoneView;
import com.xavier.backend.orders.delivery.service.DeliveryQueryService;
import com.xavier.backend.orders.delivery.service.DeliveryService;
import com.xavier.backend.orders.delivery.service.DeliveryZoneService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** POS deliveries (D127): the board, the PENDING edit and the transitions. Store and terminal come from the session. */
@RestController
class PosDeliveryController {

    static final String DELIVERIES = "/api/v1/pos/deliveries";

    private final DeliveryService deliveries;
    private final DeliveryQueryService queries;
    private final DeliveryZoneService zones;
    private final IdempotentRequests idempotent;

    PosDeliveryController(DeliveryService deliveries, DeliveryQueryService queries, DeliveryZoneService zones,
                          IdempotentRequests idempotent) {
        this.deliveries = deliveries;
        this.queries = queries;
        this.zones = zones;
        this.idempotent = idempotent;
    }

    @GetMapping(DELIVERIES)
    List<DeliveryView> board(@RequestParam(required = false) DeliveryStatus status) {
        return queries.board(status);
    }

    @GetMapping("/api/v1/pos/delivery-zones")
    List<ZoneView> zones() {
        return zones.active();
    }

    @PutMapping(DELIVERIES + "/{orderId}")
    DeliveryView update(@PathVariable UUID orderId, @Valid @RequestBody DeliveryUpdateRequest body) {
        return deliveries.update(orderId, body);
    }

    @PostMapping(DELIVERIES + "/{orderId}/dispatch")
    ResponseEntity<DeliveryView> dispatch(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody DispatchRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.delivery.dispatch", key, request, body, DeliveryView.class,
                () -> ResponseEntity.ok(deliveries.dispatch(orderId, body)));
    }

    @PostMapping(DELIVERIES + "/{orderId}/delivered")
    ResponseEntity<DeliveryView> delivered(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody DeliveredRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.delivery.delivered", key, request, body, DeliveryView.class,
                () -> ResponseEntity.ok(deliveries.delivered(orderId, body)));
    }

    @PostMapping(DELIVERIES + "/{orderId}/returned")
    ResponseEntity<DeliveryView> returned(@PathVariable UUID orderId, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody ReturnedRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.delivery.returned", key, request, body, DeliveryView.class,
                () -> ResponseEntity.ok(deliveries.returned(orderId, body)));
    }
}
