package com.xavier.backend.promotions.controller;

import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.service.PosPromotionsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PosPromotionsController {

    private final PosPromotionsService promotions;

    PosPromotionsController(PosPromotionsService promotions) {
        this.promotions = promotions;
    }

    /** The session store's published promotions, for POS price previews. */
    @GetMapping("/api/v1/pos/promotions/current")
    PromotionSnapshot current() {
        return promotions.current();
    }
}
