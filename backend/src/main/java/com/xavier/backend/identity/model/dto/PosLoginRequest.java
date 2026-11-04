package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POS login on an enrolled terminal (the terminal comes from the X-Terminal-Token header). */
public record PosLoginRequest(@NotBlank @Size(max = 20) String employeeCode, @NotBlank @Size(max = 20) String pin) {

    @Override
    public String toString() {
        return "PosLoginRequest[employeeCode=" + employeeCode + ", pin=***]";
    }
}
