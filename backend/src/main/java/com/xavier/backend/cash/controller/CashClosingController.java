package com.xavier.backend.cash.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.model.dto.ClosingView;
import com.xavier.backend.cash.model.dto.CreateClosingRequest;
import com.xavier.backend.cash.model.dto.JustifyClosingRequest;
import com.xavier.backend.cash.model.dto.RejectClosingRequest;
import com.xavier.backend.cash.service.CashClosingService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Drafting, justifying and submitting at the POS; approving and rejecting in the BackOffice. All idempotent. */
@RestController
class CashClosingController {

    static final String POS = "/api/v1/pos/cash/closings";
    static final String BACKOFFICE = "/api/v1/backoffice/cash/closings";
    private static final Map<String, Object> NO_BODY = Map.of();

    private final CashClosingService closings;
    private final Idempotent idempotent;

    CashClosingController(CashClosingService closings, IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.closings = closings;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @GetMapping(BACKOFFICE)
    PageResponse<ClosingView> list(@RequestParam UUID storeId, @RequestParam(required = false) UUID cashDayId,
                                   @RequestParam(required = false) CashClosingStatus status,
                                   @PageableDefault(sort = "createdAt") Pageable pageable) {
        return closings.list(storeId, cashDayId, status, pageable);
    }

    @PostMapping(POS)
    ResponseEntity<ClosingView> create(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CreateClosingRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.closing.create", key, request, body, ClosingView.class, () -> {
            ClosingView created = closings.create(body);
            return ResponseEntity.created(URI.create(POS + "/" + created.id())).body(created);
        });
    }

    @PutMapping(POS + "/{id}")
    ResponseEntity<ClosingView> justify(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody JustifyClosingRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.closing.justify", key, request, body, ClosingView.class,
                () -> ResponseEntity.ok(closings.justify(id, body)));
    }

    @PostMapping(POS + "/{id}/submit")
    ResponseEntity<ClosingView> submit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       HttpServletRequest request) {
        return idempotent.execute("cash.closing.submit", key, request, NO_BODY, ClosingView.class,
                () -> ResponseEntity.ok(closings.submit(id)));
    }

    @PostMapping(BACKOFFICE + "/{id}/approve")
    ResponseEntity<ClosingView> approve(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        HttpServletRequest request) {
        return idempotent.execute("cash.closing.approve", key, request, NO_BODY, ClosingView.class,
                () -> ResponseEntity.ok(closings.approve(id)));
    }

    @PostMapping(BACKOFFICE + "/{id}/reject")
    ResponseEntity<ClosingView> reject(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody RejectClosingRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.closing.reject", key, request, body, ClosingView.class,
                () -> ResponseEntity.ok(closings.reject(id, body)));
    }
}
