package com.xavier.backend.production.adapter;

import com.xavier.backend.production.model.CodePage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns a printout into ESC/POS bytes (D57): initialise, select the printer's character table, an optional copy
 * mark, one line per entry, an optional QR code (model 2), feed and a partial cut. CPU only. Characters the table
 * lacks print as {@code ?}.
 */
@Component
public class EscPosRenderer {

    /** What a reprint says on top (spec §4: a reprint is identified as a copy). */
    public static final String COPY_MARK = "*** CÓPIA ***";

    private static final byte ESC = 0x1b;
    private static final byte GS = 0x1d;
    private static final byte LF = 0x0a;

    public byte[] render(List<String> lines, String qrPayload, boolean copyMark, CodePage codePage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {ESC, '@', ESC, 't', (byte) codePage.escPosTable()});
        if (copyMark) {
            out.writeBytes(new byte[] {ESC, 'a', 1, ESC, 'E', 1});
            text(out, COPY_MARK, codePage);
            out.writeBytes(new byte[] {ESC, 'E', 0, ESC, 'a', 0});
        }
        lines.forEach(line -> text(out, line, codePage));
        if (qrPayload != null && !qrPayload.isBlank()) {
            qr(out, qrPayload);
        }
        out.writeBytes(new byte[] {ESC, 'd', 4, GS, 'V', 66, 0});
        return out.toByteArray();
    }

    private static void text(ByteArrayOutputStream out, String line, CodePage codePage) {
        out.writeBytes(line.getBytes(codePage.charset()));
        out.write(LF);
    }

    /** GS ( k: select model 2, module size 6, error correction L, store the data, print it. */
    private static void qr(ByteArrayOutputStream out, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        int length = data.length + 3;
        out.writeBytes(new byte[] {ESC, 'a', 1});
        out.writeBytes(new byte[] {GS, '(', 'k', 4, 0, '1', 'A', '2', 0});
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'C', 6});
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'E', '0'});
        out.writeBytes(new byte[] {GS, '(', 'k', (byte) (length & 0xff), (byte) (length >> 8), '1', 'P', '0'});
        out.writeBytes(data);
        out.writeBytes(new byte[] {GS, '(', 'k', 3, 0, '1', 'Q', '0'});
        out.writeBytes(new byte[] {LF, ESC, 'a', 0});
    }
}
