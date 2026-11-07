package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Replaces a promotion's scope; together the lists name at least one item, variant or family. */
public record ScopeRequest(List<@NotNull UUID> itemIds, List<@NotNull UUID> variantIds,
                           List<@NotNull UUID> familyIds, @NotNull Long version) {
}
