package com.xavier.backend.inventory.consumption.model.dto;

import java.util.UUID;

public record PolicyRuleView(UUID familyId, String trigger, boolean tracked) {
}
