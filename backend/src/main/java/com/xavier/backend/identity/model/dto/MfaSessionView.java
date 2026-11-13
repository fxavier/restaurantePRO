package com.xavier.backend.identity.model.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * The second-factor part of a {@link SessionView} (D141).
 *
 * @param stepUpValidUntil until when a sensitive permission needs no new code; {@code null} when the session never
 *                         proved a second factor
 */
public record MfaSessionView(MfaState state, Instant stepUpValidUntil) {

    public static final MfaSessionView NONE = new MfaSessionView(MfaState.NONE, null);

    public static MfaSessionView of(MfaState state) {
        return new MfaSessionView(state, null);
    }

    /** {@code state} with the step-up window of a factor proved at {@code secondFactorAt} ({@code null}: none). */
    public static MfaSessionView of(MfaState state, Instant secondFactorAt, Duration maxAge) {
        return new MfaSessionView(state, secondFactorAt == null ? null : secondFactorAt.plus(maxAge));
    }
}
