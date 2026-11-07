package com.xavier.backend.inventory.model.dto;

import java.util.List;
import java.util.UUID;

/** The movements one command posted, under one source id. */
public record MovementBatchView(String sourceType, UUID sourceId, List<MovementView> movements) {
}
