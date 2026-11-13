package com.xavier.backend.identity.service;

import com.xavier.backend.identity.model.dto.MfaState;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The second-factor facts a BackOffice session carries (D141, D142), as Spring Session JDBC attributes. Written only by
 * {@code LoginSessions} (login, verify, enrolment) and the step-up endpoint; read by {@link SessionAssurance}, the
 * filters and the session view. Values are a state name and instants, never a secret.
 */
public final class MfaSessionAttributes {

    /** {@link MfaState} name; absent = {@code NONE}. */
    public static final String MFA_STATE = "identity.mfaState";
    /** {@link Instant} the session last proved its second factor; absent = never. */
    public static final String MFA_SECOND_FACTOR_AT = "identity.mfaSecondFactorAt";
    /** {@link Instant} a {@code PENDING} login started; it expires after {@code MfaVerificationService.PENDING_TTL}. */
    public static final String MFA_PENDING_SINCE = "identity.mfaPendingSince";

    private MfaSessionAttributes() {
    }

    public static MfaState state(HttpSession session) {
        return session != null && session.getAttribute(MFA_STATE) instanceof String name ? MfaState.valueOf(name)
                : MfaState.NONE;
    }

    public static Instant secondFactorAt(HttpSession session) {
        return session != null && session.getAttribute(MFA_SECOND_FACTOR_AT) instanceof Instant at ? at : null;
    }

    public static Instant pendingSince(HttpSession session) {
        return session != null && session.getAttribute(MFA_PENDING_SINCE) instanceof Instant at ? at : null;
    }

    /** The current request's existing session, if any (never creates one). */
    public static Optional<HttpSession> current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? Optional.ofNullable(request.getRequest().getSession(false)) : Optional.empty();
    }
}
