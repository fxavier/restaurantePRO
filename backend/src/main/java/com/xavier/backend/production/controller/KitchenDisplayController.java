package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.KitchenStatus;
import com.xavier.backend.production.model.dto.KitchenCommandRequest;
import com.xavier.backend.production.model.dto.TicketView;
import com.xavier.backend.production.service.KitchenDisplayService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The kitchen display on the POS (ADR 0044): a station's rounds ({@code destination}, the production destination
 * code) by {@code status} (repeatable; none = all), oldest first, and the idempotent commands start, ready, bump and
 * recall, each with the version the screen shows.
 */
@RestController
class KitchenDisplayController {

    private static final String TICKET = ProductionTicketController.POS + "/{id}";

    private final KitchenDisplayService kitchen;
    private final IdempotentRequests idempotent;

    KitchenDisplayController(KitchenDisplayService kitchen, IdempotentRequests idempotent) {
        this.kitchen = kitchen;
        this.idempotent = idempotent;
    }

    @GetMapping(ProductionTicketController.POS + "/kds")
    PageResponse<TicketView> list(@RequestParam(required = false) String destination,
                                  @RequestParam(required = false) Set<KitchenStatus> status,
                                  @PageableDefault(size = 50, sort = "queuedAt", direction = Sort.Direction.ASC)
                                  @ParameterObject Pageable pageable) {
        return kitchen.listForPos(destination, status, pageable);
    }

    @PostMapping(TICKET + "/start")
    ResponseEntity<TicketView> start(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody KitchenCommandRequest body, HttpServletRequest request) {
        return command("start", key, request, body, () -> kitchen.start(id, body.version()));
    }

    @PostMapping(TICKET + "/ready")
    ResponseEntity<TicketView> ready(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody KitchenCommandRequest body, HttpServletRequest request) {
        return command("ready", key, request, body, () -> kitchen.ready(id, body.version()));
    }

    @PostMapping(TICKET + "/bump")
    ResponseEntity<TicketView> bump(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody KitchenCommandRequest body, HttpServletRequest request) {
        return command("bump", key, request, body, () -> kitchen.bump(id, body.version()));
    }

    @PostMapping(TICKET + "/recall")
    ResponseEntity<TicketView> recall(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @Valid @RequestBody KitchenCommandRequest body, HttpServletRequest request) {
        return command("recall", key, request, body, () -> kitchen.recall(id, body.version()));
    }

    private ResponseEntity<TicketView> command(String name, String key, HttpServletRequest request,
                                               KitchenCommandRequest body, Supplier<TicketView> action) {
        return idempotent.execute("production.ticket.kitchen." + name, key, request, body, TicketView.class,
                () -> ResponseEntity.ok(action.get()));
    }
}
