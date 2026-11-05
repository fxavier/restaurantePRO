package com.xavier.backend.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Which incomplete event publications a person resubmits (D88): either {@code publicationIds} or every one published
 * more than {@code olderThan} (an ISO-8601 duration, e.g. {@code PT1H}) ago, never both.
 */
public record ResubmitRequest(@Size(max = 500) List<@NotNull UUID> publicationIds, Duration olderThan) {

    @AssertTrue(message = "name either publicationIds or a positive olderThan, not both")
    public boolean isOneSelection() {
        boolean byIds = publicationIds != null && !publicationIds.isEmpty();
        boolean byAge = olderThan != null;
        return byIds != byAge && (!byAge || (!olderThan.isNegative() && !olderThan.isZero()));
    }
}
