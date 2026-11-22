package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.KitchenStepView;
import jakarta.persistence.Embeddable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One step of a round on the kitchen display (ADR 0044): when it happened and the employee who did it. */
@Embeddable
public record KitchenStep(Instant at, UUID by) {

    public KitchenStep {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(by, "by");
    }

    /** {@code null} for a step not reached. */
    static KitchenStepView view(KitchenStep step) {
        return step == null ? null : new KitchenStepView(step.at(), step.by());
    }
}
