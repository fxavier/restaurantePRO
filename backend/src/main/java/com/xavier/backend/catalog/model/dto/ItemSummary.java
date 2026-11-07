package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/**
 * @param productionDestination code such as {@code KITCHEN} or {@code BAR}; {@code null} = nothing to prepare
 * @param availability          ACTIVE or INACTIVE
 * @param kind                  STANDARD, MENU or FEE (D108)
 */
public record ItemSummary(UUID id, String code, String name, UUID familyId, UUID unitId, UUID taxCategoryId,
                          String productionDestination, String availability, boolean soldByWeight, String kind,
                          long version) {
}
