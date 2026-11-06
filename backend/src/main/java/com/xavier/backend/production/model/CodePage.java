package com.xavier.backend.production.model;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.Map;

/**
 * The printer's character table (D57): the ESC/POS {@code ESC t n} number and the Java charset that encodes text for
 * it. CP860 is the Portuguese table. A typographic character the table lacks prints as its ASCII fallback (BUG-0001:
 * the D97 em dash becomes a hyphen on every table but WPC1252); any other missing character prints as {@code ?}.
 */
public enum CodePage {
    CP437(0, "IBM437"),
    CP850(2, "IBM850"),
    CP860(3, "IBM860"),
    WPC1252(16, "windows-1252"),
    CP858(19, "IBM00858");

    private static final Map<Integer, String> ASCII_FALLBACKS = Map.of(
            0x2014, "-", // em dash
            0x2013, "-", // en dash
            0x2212, "-", // minus sign
            0x2018, "'",
            0x2019, "'",
            0x201C, "\"",
            0x201D, "\"",
            0x2026, "...",
            0x00A0, " ", // no-break space
            0x2022, "*"); // bullet

    private final int escPosTable;
    private final Charset charset;

    CodePage(int escPosTable, String charset) {
        this.escPosTable = escPosTable;
        this.charset = Charset.forName(charset);
    }

    public int escPosTable() {
        return escPosTable;
    }

    public Charset charset() {
        return charset;
    }

    /** The bytes the printer prints for {@code text}, with the ASCII fallback for what the table lacks. */
    public byte[] encode(String text) {
        CharsetEncoder encoder = charset.newEncoder();
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String character = Character.toString(codePoint);
            printable.append(encoder.canEncode(character)
                    ? character
                    : ASCII_FALLBACKS.getOrDefault(codePoint, character));
        });
        return printable.toString().getBytes(charset);
    }
}
