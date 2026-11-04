package com.xavier.backend.organization.model.dto;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;

/**
 * A store as other modules and the BackOffice see it. {@code currency} is the company currency (single
 * currency, spec §8.4); {@code version} is sent back on update (D17).
 */
public record StoreSummary(UUID id, String code, String name, ZoneId timezone, LocalTime businessDayCutoff,
                           Currency currency, boolean active, long version) {
}
