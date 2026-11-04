package com.xavier.backend.fiscal.model.event;

import java.util.List;

/** The text lines of a document printout and an optional QR payload ({@code null} when there is none). */
public record DocumentPrintout(List<String> lines, String qrPayload) {

    public DocumentPrintout {
        lines = List.copyOf(lines);
    }
}
