package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.settlement.model.dto.RefundRequest;
import com.xavier.backend.settlement.model.dto.RefundView;
import com.xavier.backend.settlement.service.RefundService;
import com.xavier.backend.settlement.service.RefundService.RefundApproval;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Refunds on the POS (PRD S30, D77): money back from a payment, with an approver. */
@RestController
class RefundController {

    private final RefundService refunds;
    private final IdempotentRequests idempotent;

    RefundController(RefundService refunds, IdempotentRequests idempotent) {
        this.refunds = refunds;
        this.idempotent = idempotent;
    }

    /** D40: the approver is verified first, outside any transaction; its headers never enter the request hash. */
    @PostMapping("/api/v1/pos/payments/{id}/refunds")
    ResponseEntity<RefundView> refund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @RequestHeader(value = "X-Approver-Code", required = false) String approverCode,
                                      @RequestHeader(value = "X-Approver-Pin", required = false) String approverPin,
                                      @Valid @RequestBody RefundRequest body, HttpServletRequest request) {
        RefundApproval approval = refunds.authorize(id, approverCode, approverPin);
        return idempotent.execute("settlement.payment.refund", key, request, body, RefundView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(refunds.refund(id, body, approval)));
    }
}
