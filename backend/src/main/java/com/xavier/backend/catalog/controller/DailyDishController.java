package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.catalog.model.dto.DailyDishesRequest;
import com.xavier.backend.catalog.model.dto.DailyDishesView;
import com.xavier.backend.catalog.service.DailyDishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DailyDishController {

    private final DailyDishService dailyDishes;
    private final IdempotentRequests idempotency;

    DailyDishController(DailyDishService dailyDishes, IdempotentRequests idempotency) {
        this.dailyDishes = dailyDishes;
        this.idempotency = idempotency;
    }

    @PutMapping("/api/v1/backoffice/catalog/daily-dishes")
    ResponseEntity<DailyDishesView> set(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody DailyDishesRequest body, HttpServletRequest request) {
        return idempotency.execute("catalog.daily-dishes.set", key, request, body, DailyDishesView.class,
                () -> ResponseEntity.ok(dailyDishes.set(body)));
    }
}
