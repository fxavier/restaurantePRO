package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Adds the drafts of the published macro {@code macroCode} (D109); {@code orderVersion} is the order the POS shows. */
public record MacroRequest(@NotNull Long orderVersion, @NotBlank @Size(max = 30) String macroCode) {
}
