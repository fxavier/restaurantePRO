package com.xavier.backend.dining.model.dto;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record RoomAreaSummary(UUID id, UUID storeId, String code, String name, boolean active, long version) {
}
