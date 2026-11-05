package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.orders.VoidView;
import com.xavier.backend.settlement.model.dto.SettledVoidRequest;
import com.xavier.backend.settlement.service.SettledVoidService;
import com.xavier.backend.settlement.service.SettledVoidService.VoidApproval;
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

/**
 * The coordinated void on the POS (D71): the approver is verified first, outside any transaction, and its headers
 * never enter the request hash (D40); then one idempotent transaction.
 */
@RestController
class SettledVoidController {

    private final SettledVoidService voids;
    private final IdempotentRequests idempotent;

    SettledVoidController(SettledVoidService voids, IdempotentRequests idempotent) {
        this.voids = voids;
        this.idempotent = idempotent;
    }

    @PostMapping(OrderSettlementController.ORDERS + "/{id}/settlement/voids")
    ResponseEntity<VoidView> voidLine(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      @RequestHeader(value = "X-Approver-Code", required = false) String approverCode,
                                      @RequestHeader(value = "X-Approver-Pin", required = false) String approverPin,
                                      @Valid @RequestBody SettledVoidRequest body, HttpServletRequest request) {
        VoidApproval approval = voids.authorize(id, approverCode, approverPin);
        return idempotent.execute("settlement.order.void", key, request, body, VoidView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(voids.voidLine(id, body, approval)));
    }
}
