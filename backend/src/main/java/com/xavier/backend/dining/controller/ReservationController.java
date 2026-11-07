package com.xavier.backend.dining.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.dining.model.dto.ReservationCancelRequest;
import com.xavier.backend.dining.model.dto.ReservationNoShowRequest;
import com.xavier.backend.dining.model.dto.ReservationRequest;
import com.xavier.backend.dining.model.dto.ReservationUpdateRequest;
import com.xavier.backend.dining.model.dto.ReservationView;
import com.xavier.backend.dining.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
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

/** Reservations (D126): the POS calendar and commands, and the BackOffice calendar. No DELETE: a booking is cancelled. */
@RestController
class ReservationController {

    static final String POS = "/api/v1/pos/reservations";
    static final String BACKOFFICE = "/api/v1/backoffice/reservations";

    private final ReservationService reservations;
    private final IdempotentRequests idempotency;

    ReservationController(ReservationService reservations, IdempotentRequests idempotency) {
        this.reservations = reservations;
        this.idempotency = idempotency;
    }

    @GetMapping(POS)
    List<ReservationView> day(@RequestParam LocalDate date) {
        return reservations.day(date);
    }

    @GetMapping(BACKOFFICE)
    List<ReservationView> range(@RequestParam UUID storeId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return reservations.range(storeId, from, to);
    }

    @PostMapping(POS)
    ResponseEntity<ReservationView> book(@RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody ReservationRequest body, HttpServletRequest request) {
        return idempotency.execute("dining.reservation.book", key, request, body, ReservationView.class, () -> {
            ReservationView booked = reservations.book(body);
            return ResponseEntity.created(URI.create(POS + "/" + booked.id())).body(booked);
        });
    }

    @PutMapping(POS + "/{id}")
    ReservationView update(@PathVariable UUID id, @Valid @RequestBody ReservationUpdateRequest body) {
        return reservations.update(id, body);
    }

    @PostMapping(POS + "/{id}/cancel")
    ResponseEntity<ReservationView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody ReservationCancelRequest body,
                                           HttpServletRequest request) {
        return idempotency.execute("dining.reservation.cancel", key, request, body, ReservationView.class,
                () -> ResponseEntity.ok(reservations.cancel(id, body)));
    }

    @PostMapping(POS + "/{id}/no-show")
    ResponseEntity<ReservationView> noShow(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody ReservationNoShowRequest body,
                                           HttpServletRequest request) {
        return idempotency.execute("dining.reservation.no-show", key, request, body, ReservationView.class,
                () -> ResponseEntity.ok(reservations.markNoShow(id, body.version())));
    }
}
