package com.xavier.backend.dining.controller;

import com.xavier.backend.dining.model.dto.DiningSettingsRequest;
import com.xavier.backend.dining.model.dto.DiningSettingsView;
import com.xavier.backend.dining.service.DiningSettingsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A store's reservation window and no-show grace (P47). */
@RestController
@RequestMapping("/api/v1/backoffice/dining/settings/{storeId}")
class DiningSettingsController {

    private final DiningSettingsService settings;

    DiningSettingsController(DiningSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    DiningSettingsView get(@PathVariable UUID storeId) {
        return settings.get(storeId);
    }

    @PutMapping
    DiningSettingsView update(@PathVariable UUID storeId, @Valid @RequestBody DiningSettingsRequest request) {
        return settings.update(storeId, request);
    }
}
