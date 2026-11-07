package com.xavier.backend.orders.delivery.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.orders.delivery.model.dto.DeliveryView;
import com.xavier.backend.orders.delivery.model.dto.ZoneCreateRequest;
import com.xavier.backend.orders.delivery.model.dto.ZoneUpdateRequest;
import com.xavier.backend.orders.delivery.model.dto.ZoneView;
import com.xavier.backend.orders.delivery.service.DeliveryQueryService;
import com.xavier.backend.orders.delivery.service.DeliveryZoneService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice delivery history and delivery zones (D127, D128). */
@RestController
class BackOfficeDeliveryController {

    static final String ZONES = "/api/v1/backoffice/delivery-zones";

    private final DeliveryQueryService queries;
    private final DeliveryZoneService zones;
    private final IdempotentRequests idempotent;

    BackOfficeDeliveryController(DeliveryQueryService queries, DeliveryZoneService zones,
                                 IdempotentRequests idempotent) {
        this.queries = queries;
        this.zones = zones;
        this.idempotent = idempotent;
    }

    @GetMapping("/api/v1/backoffice/deliveries")
    PageResponse<DeliveryView> history(@RequestParam UUID storeId,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) UUID courierId,
                                       @PageableDefault(size = 50) Pageable pageable) {
        return queries.history(storeId, from, to, courierId, pageable);
    }

    @GetMapping(ZONES)
    List<ZoneView> list(@RequestParam UUID storeId) {
        return zones.list(storeId);
    }

    @GetMapping(ZONES + "/{id}")
    ZoneView get(@PathVariable UUID id) {
        return zones.get(id);
    }

    @PostMapping(ZONES)
    ResponseEntity<ZoneView> create(@RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody ZoneCreateRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.delivery-zone.create", key, request, body, ZoneView.class, () -> {
            ZoneView zone = zones.create(body);
            return ResponseEntity.created(URI.create(ZONES + "/" + zone.id())).body(zone);
        });
    }

    @PutMapping(ZONES + "/{id}")
    ZoneView update(@PathVariable UUID id, @Valid @RequestBody ZoneUpdateRequest body) {
        return zones.update(id, body);
    }
}
