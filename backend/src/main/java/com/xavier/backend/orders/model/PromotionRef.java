package com.xavier.backend.orders.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The automatic promotion a line got at confirmation (D102): id, code and name as the promotion release
 * {@code releaseId} (version {@code version}) had them. Republishing never changes it (§14 #7).
 */
public record PromotionRef(UUID id, String code, String name, UUID releaseId, long version) {

    public PromotionRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(releaseId, "releaseId");
    }
}
