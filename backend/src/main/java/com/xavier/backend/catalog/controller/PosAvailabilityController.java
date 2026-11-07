package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.catalog.model.dto.AvailabilityRequest;
import com.xavier.backend.catalog.model.dto.DailyDishesView;
import com.xavier.backend.catalog.model.dto.ItemAvailabilityView;
import com.xavier.backend.catalog.service.AvailabilityService;
import com.xavier.backend.catalog.service.DailyDishService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational availability for the POS (D109): the session's store only (D39). */
@RestController
@RequestMapping("/api/v1/pos/catalog")
class PosAvailabilityController {

    private final AvailabilityService availability;
    private final DailyDishService dailyDishes;
    private final IdempotentRequests idempotency;

    PosAvailabilityController(AvailabilityService availability, DailyDishService dailyDishes,
                              IdempotentRequests idempotency) {
        this.availability = availability;
        this.dailyDishes = dailyDishes;
        this.idempotency = idempotency;
    }

    @PutMapping("/availability/{itemId}")
    ResponseEntity<ItemAvailabilityView> set(@PathVariable UUID itemId, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody AvailabilityRequest body,
                                             HttpServletRequest request) {
        return idempotency.execute("catalog.availability.set", key, request, body, ItemAvailabilityView.class,
                () -> ResponseEntity.ok(availability.set(itemId, body)));
    }

    @GetMapping("/availability")
    List<ItemAvailabilityView> soldOut() {
        return availability.soldOut();
    }

    @GetMapping("/daily-dishes")
    DailyDishesView dailyDishes(@RequestParam(required = false) LocalDate date) {
        return dailyDishes.forPos(date);
    }
}
