package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BackOffice login. {@code toString} hides the password and the enrolment code so they never reach a log.
 *
 * @param enrolmentCode optional (D141, D143): an administrator's one-time code, which lets an account without a second
 *                      factor log in from a remote address (or as a support account) to enrol
 */
public record LoginRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 200) String password,
                           @Size(max = 32) String enrolmentCode) {

    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***, enrolmentCode=" + (enrolmentCode == null ? null
                : "***") + "]";
    }
}
