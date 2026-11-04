package com.xavier.backend.fiscal.model.event;

import java.util.List;
import org.springframework.modulith.NamedInterface;

/** The text lines of a document printout and an optional QR payload ({@code null} when there is none). */
@NamedInterface("event")
public record DocumentPrintout(List<String> lines, String qrPayload) {

    public DocumentPrintout {
        lines = List.copyOf(lines);
    }
}
