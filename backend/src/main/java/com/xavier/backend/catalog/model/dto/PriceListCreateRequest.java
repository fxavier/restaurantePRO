package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** The price list of a store (one per store); its currency is the company currency. */
public record PriceListCreateRequest(@NotNull UUID storeId, @NotBlank @Size(max = 120) String name) {
}
