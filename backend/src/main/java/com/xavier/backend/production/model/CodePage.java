package com.xavier.backend.production.model;

import java.nio.charset.Charset;

/**
 * The printer's character table (D57): the ESC/POS {@code ESC t n} number and the Java charset that encodes text for
 * it. CP860 is the Portuguese table; characters a table lacks print as {@code ?}.
 */
public enum CodePage {
    CP437(0, "IBM437"),
    CP850(2, "IBM850"),
    CP860(3, "IBM860"),
    WPC1252(16, "windows-1252"),
    CP858(19, "IBM00858");

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
}
