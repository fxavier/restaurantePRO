package com.xavier.backend.production.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.production.model.KitchenCommand;
import com.xavier.backend.production.model.KitchenStatus;
import com.xavier.backend.production.model.dto.KitchenCommandRequest;
import com.xavier.backend.production.model.dto.RoundKitchenView;
import com.xavier.backend.production.model.dto.TicketView;
import com.xavier.backend.production.service.KitchenDisplayService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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
 * code) by {@code status} (repeatable; none = QUEUED, IN_PREPARATION and READY, the rounds still on the screen),
 * oldest first, and the idempotent commands start, ready, bump and
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
    PageResponse<TicketView> listForKds(@RequestParam(required = false) String destination,
                                        @RequestParam(required = false) Set<KitchenStatus> status,
                                        @PageableDefault(size = 50, sort = "queuedAt",
                                                direction = Sort.Direction.ASC)
                                        @ParameterObject Pageable pageable) {
        return kitchen.listForPos(destination, status, pageable);
    }

    /** The kitchen state of each round of an order, for the POS order panel ("pronta", "em preparação"). */
    @GetMapping(ProductionTicketController.POS + "/rounds")
    List<RoundKitchenView> roundsOfOrder(@RequestParam UUID orderId) {
        return kitchen.roundsOfOrder(orderId);
    }

    @PostMapping(TICKET + "/start")
    ResponseEntity<TicketView> kitchenStart(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody KitchenCommandRequest body,
                                            HttpServletRequest request) {
        return command(KitchenCommand.START, id, key, request, body);
    }

    @PostMapping(TICKET + "/ready")
    ResponseEntity<TicketView> kitchenReady(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody KitchenCommandRequest body,
                                            HttpServletRequest request) {
        return command(KitchenCommand.READY, id, key, request, body);
    }

    @PostMapping(TICKET + "/bump")
    ResponseEntity<TicketView> kitchenBump(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody KitchenCommandRequest body,
                                           HttpServletRequest request) {
        return command(KitchenCommand.BUMP, id, key, request, body);
    }

    @PostMapping(TICKET + "/recall")
    ResponseEntity<TicketView> kitchenRecall(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody KitchenCommandRequest body,
                                             HttpServletRequest request) {
        return command(KitchenCommand.RECALL, id, key, request, body);
    }

    private ResponseEntity<TicketView> command(KitchenCommand command, UUID id, String key, HttpServletRequest request,
                                               KitchenCommandRequest body) {
        return idempotent.execute("production.ticket.kitchen." + command.name().toLowerCase(Locale.ROOT), key,
                request, body, TicketView.class, () -> ResponseEntity.ok(kitchen.move(id, body.version(), command)));
    }
}
