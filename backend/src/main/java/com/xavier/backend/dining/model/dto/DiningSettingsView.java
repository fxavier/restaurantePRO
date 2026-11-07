package com.xavier.backend.dining.model.dto;

import java.util.UUID;

/** A store's reservation settings (P47); {@code version} is {@code null} while the store uses the defaults. */
public record DiningSettingsView(UUID storeId, int reservationWindowMinutes, int noShowGraceMinutes, Long version) {
}
