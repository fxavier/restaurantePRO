package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** A POS quick page for {@code storeId}, or for every store when it is null (installation-wide permission then). */
public record QuickPageCreateRequest(
        UUID storeId,
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @PositiveOrZero Integer position,
        @NotNull @Size(max = 200) List<@Valid @NotNull QuickPageEntryRequest> entries) {
}
