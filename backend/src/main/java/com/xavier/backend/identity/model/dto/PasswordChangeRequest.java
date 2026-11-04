package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(@NotBlank @Size(max = 200) String currentPassword,
                                    @NotBlank @Size(max = 200) String newPassword) {

    @Override
    public String toString() {
        return "PasswordChangeRequest[***]";
    }
}
