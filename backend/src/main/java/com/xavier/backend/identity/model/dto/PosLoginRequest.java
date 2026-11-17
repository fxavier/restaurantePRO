package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POS login on an enrolled terminal. The terminal comes from the request's bearer token, a devices-realm token whose
 * {@code TERMINAL} membership names it (D182), never from the body.
 */
public record PosLoginRequest(@NotBlank @Size(max = 20) String employeeCode, @NotBlank @Size(max = 20) String pin) {

    @Override
    public String toString() {
        return "PosLoginRequest[employeeCode=" + employeeCode + ", pin=***]";
    }
}
