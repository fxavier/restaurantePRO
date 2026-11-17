package com.xavier.backend.access;

import java.time.Duration;
import java.time.Instant;

/**
 * How the caller authenticated (D184, RFC 9470): the token's own {@code acr} and {@code auth_time}, set by Keycloak at
 * authentication and unchanged on refresh, never a requested value. {@link #NONE} for a POS (PIN) session and for any
 * caller without a token.
 *
 * @param acr      the level of authentication: {@code "1"} password, {@code "2"} password and a second factor;
 *                 {@code null} = none proved
 * @param authTime when the user authenticated; {@code null} = unknown
 */
public record Assurance(String acr, Instant authTime) {

    /** Nothing proved. */
    public static final Assurance NONE = new Assurance(null, null);

    /** The level of authentication that proves a second factor (Keycloak LoA 2). */
    public static final String SECOND_FACTOR = "2";

    /** A second factor was proved ({@code acr ≥ 2}). */
    public boolean hasSecondFactor() {
        if (acr == null) {
            return false;
        }
        try {
            return Integer.parseInt(acr) >= Integer.parseInt(SECOND_FACTOR);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A second factor proved at most {@code maxAge} before {@code now}. */
    public boolean isFreshSecondFactor(Duration maxAge, Instant now) {
        return hasSecondFactor() && authTime != null && !now.isAfter(authTime.plus(maxAge));
    }
}
