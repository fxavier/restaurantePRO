package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A second-factor proof: exactly one of the 6-digit TOTP {@code code} or a {@code recoveryCode}. Never logged.
 */
public record MfaCodeRequest(@Pattern(regexp = "\\d{6}", message = "must be 6 digits") String code,
                             @Size(min = 1, max = 32) String recoveryCode) {

    @AssertTrue(message = "give exactly one of code and recoveryCode")
    boolean isExactlyOne() {
        return (code == null) != (recoveryCode == null);
    }

    public int totp() {
        return Integer.parseInt(code);
    }

    @Override
    public String toString() {
        return "MfaCodeRequest[redacted]";
    }
}
