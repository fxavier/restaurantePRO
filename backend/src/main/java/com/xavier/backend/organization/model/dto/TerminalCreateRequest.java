package com.xavier.backend.organization.model.dto;

import com.xavier.backend.organization.model.DeviceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;

/**
 * Registers a device in a store; {@code code} is unique within the store. {@code kind} is {@code POS} (the default) or
 * {@code PRINT_BRIDGE}.
 */
public record TerminalCreateRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        DeviceKind kind) {

    public TerminalCreateRequest {
        kind = kind == null ? DeviceKind.POS : kind;
        // Normalised here, before validation runs on the record, so @Size checks the code as stored: upper-casing can
        // lengthen it ("ß" → "SS"), and Terminal.normalizedCode applies the same rule (FE-F21).
        code = code == null ? null : code.strip().toUpperCase(Locale.ROOT);
    }
}
