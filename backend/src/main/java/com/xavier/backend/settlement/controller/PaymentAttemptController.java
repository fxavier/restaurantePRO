package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CancelAttemptRequest;
import com.xavier.backend.settlement.model.dto.ConfirmAttemptRequest;
import com.xavier.backend.settlement.model.dto.CreateAttemptRequest;
import com.xavier.backend.settlement.model.dto.ResolveAttemptRequest;
import com.xavier.backend.settlement.service.Approval;
import com.xavier.backend.settlement.service.AttemptResolutionService;
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
    private final AttemptResolutionService resolution;
    private final Idempotent idempotent;

    PaymentAttemptController(PaymentAttemptService attempts, AttemptResolutionService resolution,
                             IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.attempts = attempts;
        this.resolution = resolution;
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

    /** EXTERNAL_TERMINAL PENDING → SUCCEEDED (D50 §7.5). */
    @PostMapping("/{id}/confirm")
    ResponseEntity<AttemptView> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody ConfirmAttemptRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.attempt.confirm", key, request, body, AttemptView.class,
                () -> ResponseEntity.ok(attempts.confirm(id, body)));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<AttemptView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CancelAttemptRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.attempt.cancel", key, request, body, AttemptView.class,
                () -> ResponseEntity.ok(attempts.cancel(id, body)));
    }

    /**
     * Without {@code outcome}: the provider is asked (D63 {@code executeSteps}, scope
     * {@code settlement.attempt.resolve}). With it: an approved reclassification (D40: the approver is verified first,
     * outside any transaction, and its headers never enter the request hash; {@code execute}, scope
     * {@code settlement.attempt.reclassify}). The two modes never share a scope (D63).
     */
    @PostMapping("/{id}/resolve")
    ResponseEntity<AttemptView> resolve(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @RequestHeader(value = "X-Approver-Code", required = false) String approverCode,
                                        @RequestHeader(value = "X-Approver-Pin", required = false) String approverPin,
                                        @Valid @RequestBody ResolveAttemptRequest body, HttpServletRequest request) {
        if (body.outcome() == null) {
            return idempotent.steps("settlement.attempt.resolve", key, request, body, AttemptView.class,
                    () -> ResponseEntity.ok(resolution.resolveByProvider(id)));
        }
        Approval approval = resolution.authorizeReclassification(id, approverCode, approverPin);
        return idempotent.execute("settlement.attempt.reclassify", key, request, body, AttemptView.class,
                () -> ResponseEntity.ok(resolution.reclassify(id, body, approval)));
    }

    @GetMapping("/{id}")
    AttemptView get(@PathVariable UUID id) {
        return attempts.get(id);
    }
}
