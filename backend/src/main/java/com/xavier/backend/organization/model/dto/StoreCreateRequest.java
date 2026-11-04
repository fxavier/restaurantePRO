package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** A new store; {@code businessDayCutoff} defaults to 05:00 when omitted. */
public record StoreCreateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 64) String timezone,
        LocalTime businessDayCutoff) {
}
