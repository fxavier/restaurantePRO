package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A device's enrolment (D182): the one-time code an administrator gave it and the public half of the P-256 key pair it
 * generated (a JWK; the private key never leaves the device).
 */
public record DeviceEnrolmentRequest(@NotBlank @Size(max = 64) String code, @NotNull Map<String, Object> publicJwk) {
}
