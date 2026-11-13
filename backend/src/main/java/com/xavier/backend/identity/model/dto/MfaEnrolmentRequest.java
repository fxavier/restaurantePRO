package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Starts an enrolment (D143). {@code enrolmentCode} is needed from a remote address and for a support account.
 * Never logged.
 */
public record MfaEnrolmentRequest(@NotBlank String currentPassword, @Size(max = 32) String enrolmentCode) {

    @Override
    public String toString() {
        return "MfaEnrolmentRequest[redacted]";
    }
}
