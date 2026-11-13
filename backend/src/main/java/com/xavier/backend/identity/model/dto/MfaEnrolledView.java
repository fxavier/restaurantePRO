package com.xavier.backend.identity.model.dto;

import java.util.List;

/**
 * The factor is active: its 10 recovery codes, shown once, and the session it restarted as VERIFIED. Not stored by
 * the idempotency mechanism (D143).
 */
public record MfaEnrolledView(List<String> recoveryCodes, SessionView session) {

    public MfaEnrolledView {
        recoveryCodes = List.copyOf(recoveryCodes);
    }

    @Override
    public String toString() {
        return "MfaEnrolledView[" + recoveryCodes.size() + " codes]";
    }
}
