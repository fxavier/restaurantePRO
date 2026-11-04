package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CreateAttemptRequest;
import com.xavier.backend.settlement.service.PaymentAttemptService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment attempts on the POS (PRD S16). */
@RestController
@RequestMapping(PaymentAttemptController.PATH)
class PaymentAttemptController {

    static final String PATH = "/api/v1/pos/payment-attempts";

    private final PaymentAttemptService attempts;
    private final Idempotent idempotent;

    PaymentAttemptController(PaymentAttemptService attempts, IdempotencyService idempotency,
                             AccessPolicy accessPolicy) {
        this.attempts = attempts;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    /** CASH → 201 SUCCEEDED, EXTERNAL_TERMINAL → 201 PENDING, INTEGRATED → 202 CREATED; each with its Location. */
    @PostMapping
    ResponseEntity<AttemptView> create(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CreateAttemptRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.attempt.create", key, request, body, AttemptView.class, () -> {
            AttemptView created = attempts.create(body);
            URI location = URI.create(PATH + "/" + created.id());
            return "CREATED".equals(created.status()) ? ResponseEntity.accepted().location(location).body(created)
                    : ResponseEntity.created(location).body(created);
        });
    }

    @GetMapping("/{id}")
    AttemptView get(@PathVariable UUID id) {
        return attempts.get(id);
    }
}
