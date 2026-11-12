package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A65, D155: one styled ticket laid out for an 80/48 and a 58/32 printer, under each cut mode, byte for byte. The
 * expected bytes are spelled out below: init, {@code ESC t}, then per line only the {@code ESC a} / {@code ESC E} /
 * {@code GS !} that change, the text and LF; a rule of {@code columns} dashes; wrap at the last space with the
 * continuation indented after "qty x " (or "   > "); a word longer than the width cut hard; DOUBLE wraps at half
 * the columns; a left/right line padded to the width; back to LEFT, the feed and the cut.
 */
class EscPosRendererGoldenTest {

    static final List<PrintLine> TICKET = List.of(
            new PrintLine.Text("BAR", Style.DOUBLE, Align.CENTER),
            new PrintLine.Text("Mesa M1 - Pedido #42", Style.BOLD, Align.LEFT),
            new PrintLine.Rule(),
            new PrintLine.Text("2 x Bacalhau à Brás com salada mista, batata e ovo", Style.TALL, Align.LEFT),
            PrintLine.text("   > SEM-GLUTEN-SEM-LACTOSE-SEM-OVO-SEM-AMENDOIM-SEM-SAL"),
            new PrintLine.Text("TOTAL A PAGAR 116.00 MZN", Style.DOUBLE, Align.RIGHT),
            new PrintLine.LeftRight("Cerveja 2M", "58.00", Style.NORMAL),
            new PrintLine.Text("Obrigado", Style.NORMAL, Align.CENTER));

    static final byte[] CUT_PARTIAL = {0x1d, 0x56, 66, 0};
    static final byte[] CUT_FULL = {0x1d, 0x56, 65, 0};

    final EscPosRenderer renderer = new EscPosRenderer();

    @ParameterizedTest
    @EnumSource(CutMode.class)
    void anEightyMillimetrePrinterWith48Columns(CutMode cut) {
        byte[] expected = bytes(
                "1b40", "1b7402",
                "1b6101", "1d2111", "BAR", "0a",
                "1b6100", "1b4501", "1d2100", "Mesa M1 - Pedido #42", "0a",
                "1b4500", "-".repeat(48), "0a",
                "1d2101", "2 x Bacalhau ", "85", " Br", "a0", "s com salada mista, batata e", "0a",
                "    ovo", "0a",
                "1d2100", "   > SEM-GLUTEN-SEM-LACTOSE-SEM-OVO-SEM-AMENDOIM", "0a",
                "     -SEM-SAL", "0a",
                "1b6102", "1d2111", "TOTAL A PAGAR 116.00 MZN", "0a",
                "1b6100", "1d2100", "Cerveja 2M" + " ".repeat(33) + "58.00", "0a",
                "1b6101", "Obrigado", "0a",
                "1b6100", "1b6404", cut(cut));

        assertThat(hex(render(80, 48, cut))).isEqualTo(hex(expected));
    }

    @ParameterizedTest
    @EnumSource(CutMode.class)
    void aFiftyEightMillimetrePrinterWith32Columns(CutMode cut) {
        byte[] expected = bytes(
                "1b40", "1b7402",
                "1b6101", "1d2111", "BAR", "0a",
                "1b6100", "1b4501", "1d2100", "Mesa M1 - Pedido #42", "0a",
                "1b4500", "-".repeat(32), "0a",
                "1d2101", "2 x Bacalhau ", "85", " Br", "a0", "s com salada", "0a",
                "    mista, batata e ovo", "0a",
                "1d2100", "   > SEM-GLUTEN-SEM-LACTOSE-SEM-", "0a",
                "     OVO-SEM-AMENDOIM-SEM-SAL", "0a",
                "1b6102", "1d2111", "TOTAL A PAGAR", "0a",
                "116.00 MZN", "0a",
                "1b6100", "1d2100", "Cerveja 2M" + " ".repeat(17) + "58.00", "0a",
                "1b6101", "Obrigado", "0a",
                "1b6100", "1b6404", cut(cut));

        assertThat(hex(render(58, 32, cut))).isEqualTo(hex(expected));
    }

    private byte[] render(int width, int columns, CutMode cut) {
        PrinterProfile profile = new PrinterProfile(width, columns, null, cut, 4, false, null, 100);
        return renderer.render(Printout.styled(TICKET), profile, CodePage.CP850, false);
    }

    private static String cut(CutMode cut) {
        return switch (cut) {
            case PARTIAL -> hex(CUT_PARTIAL);
            case FULL -> hex(CUT_FULL);
            case NONE -> "";
        };
    }

    /** A part made only of lowercase hex pairs is bytes (a command, LF, an encoded accent); any other is ASCII. */
    static byte[] bytes(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String part : parts) {
            out.writeBytes(part.matches("([0-9a-f]{2})+") ? HexFormat.of().parseHex(part)
                    : part.getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
