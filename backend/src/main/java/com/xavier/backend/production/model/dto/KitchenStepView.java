package com.xavier.backend.production.model.dto;

import java.time.Instant;
import java.util.UUID;

/** One step of a round on the kitchen display (ADR 0044): when it happened and the employee who did it. */
public record KitchenStepView(Instant at, UUID by) {
}
