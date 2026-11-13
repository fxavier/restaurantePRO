package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new network printer (ESC/POS over TCP, usually port 9100); it starts NORMAL and active. The profile fields
 * (D154) are optional: an omitted one takes its default, and the ranges are checked as a business rule (422).
 */
public record PrinterCreateRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 255) String host,
        @NotNull @Min(1) @Max(65535) Integer port,
        @NotNull CodePage codePage,
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
