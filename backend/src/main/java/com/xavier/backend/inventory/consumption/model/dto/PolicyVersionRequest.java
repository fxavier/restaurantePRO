package com.xavier.backend.inventory.consumption.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code version} is the draft's optimistic-lock version on PUT, ignored on POST. */
public record PolicyVersionRequest(@NotEmpty @Size(max = 500) List<@NotNull @Valid PolicyRuleRequest> rules,
                                   Long version) {
}
