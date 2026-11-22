package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.KitchenStatus;
import java.time.Instant;

/**
 * A round's progress on the kitchen display (ADR 0044). {@code version} goes back with the next command (stale →
 * 409). Each step ({@code at}, {@code by}) is the time and employee of the step that reached the current status, or
 * {@code null} when not reached; a recall clears the steps it undid and is itself the step {@code recalled}.
 */
public record KitchenProgressView(KitchenStatus status, long version, Instant queuedAt, KitchenStepView started,
                                  KitchenStepView ready, KitchenStepView bumped, KitchenStepView recalled) {
}
