package com.xavier.backend.inventory.consumption.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyVersionView(UUID id, int versionNo, String status, Instant effectiveFrom, Instant effectiveTo,
                                Instant activatedAt, long version, List<PolicyRuleView> rules) {
}
