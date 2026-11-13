package com.xavier.backend.production.model.dto;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import jakarta.validation.constraints.Max;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;

/**
 * {@code active=false} deactivates the printer (its jobs wait); it is never deleted. The mode has its own PUT. A
 * profile field (D154) the client omits keeps its current value (PRD S59, amended 2026-11-13); an explicit
 * {@code null} on {@code codeTable} or {@code drawerPin} clears it. The result is checked as a whole (422).
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
        /** {@code null}: omitted, kept; empty: an explicit JSON null, cleared. */
        @JsonDeserialize(using = ClearableIntDeserializer.class) Optional<Integer> codeTable,
        CutMode cutMode,
        Integer feedLines,
        Boolean statusProbe,
        /** {@code null}: omitted, kept; empty: an explicit JSON null, cleared. */
        @JsonDeserialize(using = ClearableIntDeserializer.class) Optional<Integer> drawerPin,
        Integer drawerPulseMs) {

    /** The {@code current} profile with the fields this request sends. */
    public PrinterProfile profile(PrinterProfile current) {
        return new PrinterProfile(
                paperWidthMm != null ? paperWidthMm : current.paperWidthMm(),
                columns != null ? columns : current.columns(),
                codeTable != null ? codeTable.orElse(null) : current.codeTable(),
                cutMode != null ? cutMode : current.cutMode(),
                feedLines != null ? feedLines : current.feedLines(),
                statusProbe != null ? statusProbe : current.statusProbe(),
                drawerPin != null ? drawerPin.orElse(null) : current.drawerPin(),
                drawerPulseMs != null ? drawerPulseMs : current.drawerPulseMs());
    }
}
