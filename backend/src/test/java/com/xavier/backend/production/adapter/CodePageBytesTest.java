package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D156, P60, A65: Portuguese letters print as single bytes of the printer's table, never UTF-8. The expected bytes
 * are the IBM code page charts, written out here so a wrong table cannot pass.
 */
class CodePageBytesTest {

    static final String LETTERS = "ãõçÃÕÇ";

    final EscPosRenderer renderer = new EscPosRenderer();

    @Test
    void cp850SelectsTable2AndEncodesTheLettersInIt() {
        String bytes = hex(render(PrinterProfile.DEFAULT, CodePage.CP850));

        assertThat(bytes).startsWith("1b40" + "1b7402").contains("c6" + "e4" + "87" + "c7" + "e5" + "80" + "0a");
    }

    @Test
    void cp860SelectsTable3AndEncodesTheLettersInIt() {
        String bytes = hex(render(PrinterProfile.DEFAULT, CodePage.CP860));

        assertThat(bytes).startsWith("1b40" + "1b7403").contains("84" + "94" + "87" + "8e" + "99" + "80" + "0a");
    }

    @Test
    void aCodeTableOverrideIsTheNumberSentInEscT() {
        PrinterProfile vendor = new PrinterProfile(80, 48, 17, CutMode.PARTIAL, 4, false, null, 100);

        String bytes = hex(render(vendor, CodePage.CP850));

        assertThat(bytes).startsWith("1b40" + "1b7411").contains("c6" + "e4" + "87" + "c7" + "e5" + "80");
    }

    @Test
    void noLetterIsSentAsUtf8() {
        String bytes = hex(render(PrinterProfile.DEFAULT, CodePage.CP850));

        assertThat(bytes).doesNotContain("c3a3").doesNotContain("c3b5").doesNotContain("c3a7");
    }

    private byte[] render(PrinterProfile profile, CodePage codePage) {
        return renderer.render(Printout.plain(List.of(LETTERS), null), profile, codePage, false);
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
