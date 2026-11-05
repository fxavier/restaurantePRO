package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.settlement.model.dto.CreditPaymentRequest;
import com.xavier.backend.settlement.service.PaymentAttemptService;
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

/**
 * Debt payments on the POS (PRD S29, D75): a payment attempt on a customer's credit account. It is then confirmed,
 * cancelled or resolved like any attempt, under {@code /api/v1/pos/payment-attempts/{id}}.
 */
@RestController
class DebtPaymentController {

    private final PaymentAttemptService attempts;
    private final IdempotentRequests idempotent;

    DebtPaymentController(PaymentAttemptService attempts, IdempotentRequests idempotent) {
        this.attempts = attempts;
        this.idempotent = idempotent;
    }

    /** CASH → 201 SUCCEEDED, EXTERNAL_TERMINAL → 201 PENDING, INTEGRATED → 202 CREATED, as an order's attempt. */
    @PostMapping("/api/v1/pos/customers/{id}/credit-payments")
    ResponseEntity<AttemptView> pay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody CreditPaymentRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.credit.payment", key, request, body, AttemptView.class, () -> {
            AttemptView created = attempts.createForCustomer(id, body);
            URI location = URI.create(PaymentAttemptController.PATH + "/" + created.id());
            return "CREATED".equals(created.status()) ? ResponseEntity.accepted().location(location).body(created)
                    : ResponseEntity.created(location).body(created);
        });
    }
}
