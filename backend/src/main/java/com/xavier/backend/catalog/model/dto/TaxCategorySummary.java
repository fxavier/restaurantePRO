package com.xavier.backend.catalog.model.dto;

import java.util.List;
import java.util.UUID;

/** {@code rates} are ordered by {@code effectiveFrom}. */
public record TaxCategorySummary(UUID id, String code, String name, List<TaxRateSummary> rates, long version) {
}
