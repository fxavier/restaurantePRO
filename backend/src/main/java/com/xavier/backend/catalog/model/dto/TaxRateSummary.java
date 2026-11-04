package com.xavier.backend.catalog.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code ratePercent} is a decimal percent: 16 means 16%. */
public record TaxRateSummary(UUID id, BigDecimal ratePercent, Instant effectiveFrom) {
}
