package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

public record UnitSummary(UUID id, String code, String name, long version) {
}
