package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/** D57: the text layout in the printer's code page, the copy mark, an optional QR code and the cut. */
class EscPosRendererTest {

    final EscPosRenderer renderer = new EscPosRenderer();

    @Test
    void initialisesSelectsTheCodePageWritesEachLineAndCuts() {
        byte[] bytes = renderer.render(List.of("BAR", "2 x Cerveja"), null, false, CodePage.CP860);

        assertThat(hex(bytes)).startsWith("1b40" + "1b7403")
                .contains(hex("BAR\n2 x Cerveja\n".getBytes(CodePage.CP860.charset())))
                .endsWith("1b6404" + "1d564200");
    }

    @Test
    void encodesPortugueseTextInTheSelectedTable() {
        byte[] bytes = renderer.render(List.of("Ação"), null, false, CodePage.CP860);

        // CP860: ç = 0x87, ã = 0x84
        assertThat(hex(bytes)).contains("41" + "87" + "84" + "6f" + "0a");
        assertThat(hex(renderer.render(List.of("x"), null, false, CodePage.CP850))).startsWith("1b40" + "1b7402");
    }

    @Test
    void aCopyCarriesTheCopyMarkBeforeItsText() {
        String copy = hex(renderer.render(List.of("BAR"), null, true, CodePage.CP860));
        String original = hex(renderer.render(List.of("BAR"), null, false, CodePage.CP860));

        String mark = hex(EscPosRenderer.COPY_MARK.getBytes(CodePage.CP860.charset()));
        assertThat(copy).contains(mark);
        assertThat(copy.indexOf(mark)).isLessThan(copy.indexOf(hex("BAR\n".getBytes())));
        assertThat(original).doesNotContain(mark);
    }

    @Test
    void aQrPayloadIsStoredAndPrintedAsAModelTwoQrCode() {
        String bytes = hex(renderer.render(List.of("TOTAL 116.00"), "https://x/q?d=1", false, CodePage.CP860));

        ByteArrayOutputStream store = new ByteArrayOutputStream();
        int length = "https://x/q?d=1".length() + 3;
        store.writeBytes(new byte[] {0x1d, 0x28, 0x6b, (byte) length, 0, 0x31, 0x50, 0x30});
        store.writeBytes("https://x/q?d=1".getBytes());
        assertThat(bytes).contains("1d286b0400314132" + "00").contains(hex(store.toByteArray()))
                .contains("1d286b0300315130");
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
