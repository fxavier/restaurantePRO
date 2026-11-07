package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A macro: lines the POS adds as drafts in one step (D109). Company-wide. */
public record MacroCreateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull ItemLineRequest> entries) {
}
