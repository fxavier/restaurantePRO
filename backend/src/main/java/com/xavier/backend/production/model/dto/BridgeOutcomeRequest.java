package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.adapter.SendOutcome;
import jakarta.validation.constraints.NotNull;

/**
 * What a bridge's send of a claimed job achieved (D57, D157, D187): {@code ACCEPTED}, {@code NOT_CONNECTED}, a
 * status-probe refusal ({@code NOT_READY_*}) or {@code INTERRUPTED_AFTER_WRITE}; {@code paperNearEnd} when the probe
 * warned (the job was still sent).
 */
public record BridgeOutcomeRequest(@NotNull SendOutcome outcome, boolean paperNearEnd) {
}
