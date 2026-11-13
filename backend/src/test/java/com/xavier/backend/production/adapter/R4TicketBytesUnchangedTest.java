package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D154, A65: a ticket stored PLAIN before R5 (every R4 ticket kind, and fiscal printouts) prints byte-identical to R4
 * under the default profile. The expected bytes were produced by the R4 renderer
 * ({@code EscPosRenderer.render(List<String>, String, boolean, CodePage)} at commit 12885fc) and are frozen here.
 */
class R4TicketBytesUnchangedTest {

    static final String RULE = "-".repeat(32);

    final EscPosRenderer renderer = new EscPosRenderer();

    @Test
    void aRound() {
        assertR4(List.of("BAR", "Mesa M1 - Pedido #42", "Ronda 2", "04/11/2026 12:05", RULE, "2 x Cerveja 2M",
                "1.5 x Pizza (Grande)", "   > sem cebola", "   + 2 x Sumo"), null, false, CodePage.CP860,
                "1b401b74034241520a4d657361204d31202d2050656469646f202334320a526f6e646120320a30342f31312f323032362031323a30350a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a3220782043657276656a6120324d0a312e3520782050697a7a6120284772616e6465290a2020203e2073656d206365626f6c610a2020202b203220782053756d6f0a1b64041d564200");
    }

    @Test
    void aRoundReprintedWithTheCopyMark() {
        assertR4(List.of("BAR", "Mesa M1 - Pedido #42", "Ronda 2", "04/11/2026 12:05", RULE, "2 x Cerveja 2M"),
                null, true, CodePage.CP860,
                "1b401b74031b61011b45012a2a2a20439f504941202a2a2a0a1b45001b61004241520a4d657361204d31202d2050656469646f202334320a526f6e646120320a30342f31312f323032362031323a30350a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a3220782043657276656a6120324d0a1b64041d564200");
    }

    @Test
    void aVoid() {
        assertR4(List.of("*** ANULAÇÃO ***", "BAR", "Mesa M1 - Pedido #42", "04/11/2026 12:05", RULE,
                "-1 x Cerveja 2M", "Motivo: cliente desistiu"), null, false, CodePage.CP860,
                "1b401b74032a2a2a20414e554c41808e4f202a2a2a0a4241520a4d657361204d31202d2050656469646f202334320a30342f31312f323032362031323a30350a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a2d3120782043657276656a6120324d0a4d6f7469766f3a20636c69656e74652064657369737469750a1b64041d564200");
    }

    @Test
    void aSubtotal() {
        assertR4(List.of("SUBTOTAL", "NÃO SERVE DE FATURA", "Mesa M1 - Pedido #42", RULE,
                "2 x Cerveja 2M 58.00 = 116.00", RULE, "TOTAL 116.00 MZN"), null, false, CodePage.CP850,
                "1b401b7402535542544f54414c0a4ec74f205345525645204445204641545552410a4d657361204d31202d2050656469646f202334320a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a3220782043657276656a6120324d2035382e3030203d203131362e30300a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a544f54414c203131362e3030204d5a4e0a1b64041d564200");
    }

    @Test
    void aDocumentWithItsQrCode() {
        assertR4(List.of("Restaurante Sol", "Contribuinte: 400123456", "FT A/1", RULE,
                "Pagamento misto — Numerário: 20.00", "TOTAL 116.00 MZN"), "https://x/q?d=1", false, CodePage.CP860,
                "1b401b740352657374617572616e746520536f6c0a436f6e7472696275696e74653a203430303132333435360a465420412f310a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a506167616d656e746f206d6973746f202d204e756d6572a072696f3a2032302e30300a544f54414c203131362e3030204d5a4e0a1b61011d286b0400314132001d286b03003143061d286b03003145301d286b120031503068747470733a2f2f782f713f643d311d286b03003151300a1b61001b64041d564200");
    }

    @Test
    void aTestPage() {
        assertR4(List.of("TESTE DE IMPRESSÃO", "Impressora K1", "04/11/2026 12:05", "Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç"),
                null, false, CodePage.CP850,
                "1b401b7402544553544520444520494d5052455353c74f0a496d70726573736f7261204b310a30342f31312f323032362031323a30350a4163656e746f733a20b590d6e0e920b6d2e220c7e520802085870a1b64041d564200");
    }

    @Test
    void aDeliverySlip() {
        assertR4(List.of("GUIA DE ENTREGA", "NÃO SERVE DE FATURA", "ENTREGA #12", "04/11/2026 12:05", RULE,
                "Cliente: Ana Silva", "Rua da Entrega 7", "Zona: Z1", RULE, "1 x Pizza", RULE, "A PAGAR 9.00 MZN"),
                null, false, CodePage.CP860,
                "1b401b74034755494120444520454e54524547410a4e8e4f205345525645204445204641545552410a454e5452454741202331320a30342f31312f323032362031323a30350a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a436c69656e74653a20416e612053696c76610a52756120646120456e747265676120370a5a6f6e613a205a310a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a3120782050697a7a610a2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d2d0a4120504147415220392e3030204d5a4e0a1b64041d564200");
    }

    private void assertR4(List<String> lines, String qr, boolean copy, CodePage codePage, String r4Hex) {
        byte[] bytes = renderer.render(Printout.plain(lines, qr), PrinterProfile.DEFAULT, codePage, copy);

        assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(r4Hex);
    }
}
