package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.settlement.model.dto.CancelCreditChargeRequest;
import com.xavier.backend.settlement.model.dto.CreditChargeRequest;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
import com.xavier.backend.settlement.service.CreditSaleService;
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

/** Credit sales on the POS (PRD S29, D74): put an order's target on a customer's account, or cancel it before issue. */
@RestController
class CreditChargeController {

    static final String CHARGES = "/api/v1/pos/credit-charges";

    private final CreditSaleService credit;
    private final IdempotentRequests idempotent;

    CreditChargeController(CreditSaleService credit, IdempotentRequests idempotent) {
        this.credit = credit;
        this.idempotent = idempotent;
    }

    /** D63 (s1 reserve and draft, s2 issue, s3 post): 201 POSTED; 422 {@code FISCAL_PENDING} is retried. */
    @PostMapping(OrderSettlementController.ORDERS + "/{id}/credit-charges")
    ResponseEntity<CreditChargeView> charge(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody CreditChargeRequest body, HttpServletRequest request) {
        return idempotent.steps(CreditSaleService.CHARGE_OPERATION, key, request, body, CreditChargeView.class, () -> {
            CreditChargeView posted = credit.charge(id, body, key);
            return ResponseEntity.created(URI.create(CHARGES + "/" + posted.id())).body(posted);
        });
    }

    @PostMapping(CHARGES + "/{id}/cancel")
    ResponseEntity<CreditChargeView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody CancelCreditChargeRequest body,
                                            HttpServletRequest request) {
        return idempotent.execute("settlement.credit.cancel", key, request, body, CreditChargeView.class,
                () -> ResponseEntity.ok(credit.cancel(id, body)));
    }
}
