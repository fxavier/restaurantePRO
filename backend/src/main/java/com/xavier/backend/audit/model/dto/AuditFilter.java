package com.xavier.backend.audit.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Which audit rows to read or export (D82): those with {@code from <= occurredAt < to}, a span of at most 92 days,
 * narrowed by every other filter that is given. Without {@code storeId} the rows are those of every store the caller
 * may see (and the store-less rows for an installation-wide auditor).
 *
 * @param actorKind {@code ACCOUNT}, {@code EMPLOYEE} or {@code SYSTEM}
 */
public record AuditFilter(UUID storeId, @NotNull Instant from, @NotNull Instant to, UUID actorId,
                          @Pattern(regexp = "ACCOUNT|EMPLOYEE|SYSTEM") String actorKind,
                          @Size(max = 100) String action, @Size(max = 100) String entityType,
                          @Size(max = 100) String entityId) {

    @AssertTrue(message = "to must be after from")
    public boolean isRangeOrdered() {
        return from == null || to == null || from.isBefore(to);
    }
}
