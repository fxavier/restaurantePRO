package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** The first code from the authenticator app, which activates the pending factor. Never logged. */
public record MfaConfirmRequest(@NotNull @Pattern(regexp = "\\d{6}", message = "must be 6 digits") String code) {

    public int totp() {
        return Integer.parseInt(code);
    }

    @Override
    public String toString() {
        return "MfaConfirmRequest[redacted]";
    }
}
