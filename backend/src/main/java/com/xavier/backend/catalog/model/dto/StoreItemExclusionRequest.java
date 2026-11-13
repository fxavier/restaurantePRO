package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code PUT …/item-exclusions/{itemId}}; the body and {@code reason} are optional. */
public record StoreItemExclusionRequest(@Size(max = 200) String reason) {

    public static final StoreItemExclusionRequest EMPTY = new StoreItemExclusionRequest(null);
}
