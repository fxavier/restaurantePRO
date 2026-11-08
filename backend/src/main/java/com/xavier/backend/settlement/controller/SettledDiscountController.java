package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.model.dto.SettledDiscountRequest;
import com.xavier.backend.settlement.service.SettledDiscountService;
import com.xavier.backend.settlement.service.SettledDiscountService.DiscountApproval;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The discount under the PAYMENT lock on the POS (D105): the approver, when sent, is verified first, outside any
 * transaction, and its headers never enter the request hash (D40); then one idempotent transaction. 200 with the
 * order's settlement view after the discount.
 */
@RestController
class SettledDiscountController {

    private final SettledDiscountService discounts;
    private final IdempotentRequests idempotent;

    SettledDiscountController(SettledDiscountService discounts, IdempotentRequests idempotent) {
        this.discounts = discounts;
        this.idempotent = idempotent;
    }

    @PostMapping(OrderSettlementController.ORDERS + "/{id}/settlement/discounts")
    ResponseEntity<OrderSettlementView> discount(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                 @RequestHeader(value = "X-Approver-Code", required = false)
                                                 String approverCode,
                                                 @RequestHeader(value = "X-Approver-Pin", required = false)
                                                 String approverPin,
                                                 @Valid @RequestBody SettledDiscountRequest body,
                                                 HttpServletRequest request) {
        DiscountApproval approval = discounts.authorize(id, body.kind(), approverCode, approverPin);
        return idempotent.execute("settlement.order.discount", key, request, body, OrderSettlementView.class,
                () -> ResponseEntity.ok(discounts.discount(id, body, approval)));
    }
}
