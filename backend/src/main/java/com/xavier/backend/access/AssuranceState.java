package com.xavier.backend.access;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What {@link AccessPolicy} needs to decide a step-up (D142).
 *
 * @param enforced       step-up applies: the installation's MFA mode is not {@code OFF}. Reported from the mode
 *                       whatever the actor kind, so a POS (EMPLOYEE) session is enforced too when the mode is
 *                       {@code SENSITIVE} or {@code ALL_BACKOFFICE} (D142 amended, S55 review)
 * @param enrolled       the account has an active second factor
 * @param secondFactorAt when this session last proved the second factor; {@code null} = never
 * @param maxAge         how long a proof stays fresh ({@code restaurantpro.security.mfa.step-up-max-age})
 */
public record AssuranceState(boolean enforced, boolean enrolled, Instant secondFactorAt, Duration maxAge) {

    /** Step-up is not enforced (MFA {@code OFF}, or no {@link AuthenticationAssurance} bean). */
    public static final AssuranceState NOT_ENFORCED = new AssuranceState(false, false, null, Duration.ZERO);

    public AssuranceState {
        Objects.requireNonNull(maxAge, "maxAge");
    }
}
