package com.xavier.backend.reporting.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Map;

/**
 * A report to export as CSV (D86) with the query parameters of its {@code GET}: {@code storeId}, {@code from},
 * {@code to}, and {@code groupBy}, {@code by} or {@code limit} where the report takes them.
 */
public record ExportRequest(@NotNull @Pattern(regexp = "sales|taxes|top-items|receipts|voids|margins|discounts") String report,
                            @NotNull Map<String, String> params) {
}
