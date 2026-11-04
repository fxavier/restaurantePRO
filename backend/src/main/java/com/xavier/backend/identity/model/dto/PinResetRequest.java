package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** A manager gives an employee a new PIN (4 to 8 digits); it also unlocks the employee. */
public record PinResetRequest(@NotNull @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String pin) {

    @Override
    public String toString() {
        return "PinResetRequest[***]";
    }
}
