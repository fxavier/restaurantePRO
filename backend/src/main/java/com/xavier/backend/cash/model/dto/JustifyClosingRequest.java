package com.xavier.backend.cash.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Sets the justification of some lines of a DRAFT closing; {@code version} is the closing's (D17). */
public record JustifyClosingRequest(@NotNull Long version, @NotNull List<@Valid Justification> justifications) {
}
