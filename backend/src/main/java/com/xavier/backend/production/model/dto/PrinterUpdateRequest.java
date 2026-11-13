package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code active=false} deactivates the printer (its jobs wait); it is never deleted. The mode has its own PUT. Like
 * the other fields, the profile (D154) is replaced as a whole: an omitted profile field takes its default.
 */
public record PrinterUpdateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotNull CodePage codePage,
        boolean active,
        @NotNull Long version,
        Integer paperWidthMm,
        Integer columns,
        Integer codeTable,
        CutMode cutMode,
        Integer feedLines,
        Boolean statusProbe,
        Integer drawerPin,
        Integer drawerPulseMs) {

    public PrinterProfile profile() {
        return ProfileFields.toProfile(paperWidthMm, columns, codeTable, cutMode, feedLines, statusProbe, drawerPin,
                drawerPulseMs);
    }
}
