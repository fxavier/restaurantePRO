package com.xavier.backend.dining.model.dto;

import java.util.UUID;

/**
 * What orders needs to know about a table (S13): its store, whether it and its area are active, whether it is
 * locked (a locked table refuses new lines, rounds and incoming transfers), and the order of its open occupation
 * ({@code null} when the table is FREE).
 */
public record TableState(UUID tableId, UUID storeId, boolean active, boolean areaActive, boolean locked,
                         UUID openOrderId) {
}
