package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.Size;

/** Optional body of a publish; {@code note} is recorded in the audit trail. Part of the idempotency identity. */
public record PublishRequest(@Size(max = 500) String note) {

    public static final PublishRequest EMPTY = new PublishRequest(null);
}
