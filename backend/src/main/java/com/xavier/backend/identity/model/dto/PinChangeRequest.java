package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** An employee replaces the PIN a manager set with their own (4 to 8 digits). */
public record PinChangeRequest(
        @NotNull @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String newPin) {

    @Override
    public String toString() {
        return "PinChangeRequest[***]";
    }
}
