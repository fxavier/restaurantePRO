package com.xavier.backend.promotions.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.promotions.model.dto.DiscountLimitsRequest;
import com.xavier.backend.promotions.model.dto.DiscountLimitsView;
import com.xavier.backend.promotions.service.DiscountLimitsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A store's manual discount limits (D106), BackOffice. */
@RestController
class DiscountLimitsController {

    static final String PATH = PromotionController.PATH + "/discount-limits/{storeId}";

    private final DiscountLimitsService limits;
    private final IdempotentRequests idempotent;

    DiscountLimitsController(DiscountLimitsService limits, IdempotentRequests idempotent) {
        this.limits = limits;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    DiscountLimitsView get(@PathVariable UUID storeId) {
        return limits.get(storeId);
    }

    @PutMapping(PATH)
    ResponseEntity<DiscountLimitsView> put(@PathVariable UUID storeId, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody DiscountLimitsRequest body, HttpServletRequest request) {
        return idempotent.execute("promotions.discount-limits.put", key, request, body, DiscountLimitsView.class,
                () -> ResponseEntity.ok(limits.put(storeId, body)));
    }
}
