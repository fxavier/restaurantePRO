package com.xavier.backend.production.model;

import java.util.List;
import java.util.Objects;

/** What a ticket prints: its lines in a format, and an optional QR payload (D57). */
public record Printout(ContentFormat format, List<PrintLine> lines, String qrPayload) {

    public Printout {
        Objects.requireNonNull(format, "format");
        lines = List.copyOf(lines);
    }

    /** Text lines as a fiscal adapter or R4 laid them out: each a NORMAL, LEFT text. */
    public static Printout plain(List<String> lines, String qrPayload) {
        return new Printout(ContentFormat.PLAIN, lines.stream().<PrintLine>map(PrintLine::text).toList(), qrPayload);
    }

    /** A drawer kick (D158): its one line names it; the bytes come from the printer's profile at send time. */
    public static Printout kick(String label) {
        return new Printout(ContentFormat.KICK, List.of(PrintLine.text(label)), null);
    }

    public static Printout styled(List<PrintLine> lines) {
        return new Printout(ContentFormat.STYLED_V1, lines, null);
    }

    public List<String> plainText() {
        return lines.stream().map(PrintLine::plainText).toList();
    }

    public Printout withoutLineBreaks() {
        return new Printout(format, lines.stream().map(PrintLine::withoutLineBreaks).toList(), qrPayload);
    }
}
