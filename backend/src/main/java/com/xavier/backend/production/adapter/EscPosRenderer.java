package com.xavier.backend.production.adapter;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns a printout into ESC/POS bytes for the printer that prints it (D57, D155): initialise, select its character
 * table, an optional copy mark, the lines laid out at its width, an optional QR code (model 2), the feed and the cut
 * of its profile. CPU only. Alignment and style commands are written only when they change from the state after
 * {@code ESC @} (NORMAL, LEFT), so a PLAIN printout carries none and prints as in R4. Text is encoded by
 * {@link CodePage#encode} (single-byte tables only, D156).
 */
@Component
public class EscPosRenderer {

    /** What a reprint says on top (spec §4: a reprint is identified as a copy). */
    public static final String COPY_MARK = "*** CÓPIA ***";

    private static final byte ESC = 0x1b;
    private static final byte GS = 0x1d;
    private static final byte LF = 0x0a;

    /** The text after "qty x " (an item, a component, a voided quantity) or after "   > " (a note). */
    private static final Pattern CONTINUATION_INDENT = Pattern.compile("^ *(?:[>+] +)?(?:-?[0-9][0-9.]* x )?");

    public byte[] render(Printout printout, PrinterProfile profile, CodePage codePage, boolean copyMark) {
        Writer writer = new Writer(profile.columns(), codePage);
        writer.out.writeBytes(new byte[] {ESC, '@', ESC, 't', (byte) profile.escPosTable(codePage)});
        if (copyMark) {
            writer.copyMark();
        }
        printout.lines().forEach(writer::line);
        writer.select(Align.LEFT, Style.NORMAL);
        if (printout.qrPayload() != null && !printout.qrPayload().isBlank()) {
            qr(writer.out, printout.qrPayload());
        }
        writer.out.writeBytes(new byte[] {ESC, 'd', (byte) profile.feedLines()});
        writer.out.writeBytes(switch (profile.cutMode()) {
            case PARTIAL -> new byte[] {GS, 'V', 66, 0};
            case FULL -> new byte[] {GS, 'V', 65, 0};
            case NONE -> new byte[0];
        });
        return writer.out.toByteArray();
    }

    /** The printer's state as this ticket left it, so a command is written only when it changes. */
    private static final class Writer {

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int columns;
        final CodePage codePage;
        Align align = Align.LEFT;
        boolean bold;
        int size;

        Writer(int columns, CodePage codePage) {
            this.columns = columns;
            this.codePage = codePage;
        }

        /** BOLD and CENTER, then back to the initial state (spec §4), byte-identical to R4. */
        void copyMark() {
            out.writeBytes(new byte[] {ESC, 'a', 1, ESC, 'E', 1});
            text(COPY_MARK);
            out.writeBytes(new byte[] {ESC, 'E', 0, ESC, 'a', 0});
        }

        void line(PrintLine line) {
            switch (line) {
                case PrintLine.Text text -> {
                    select(text.align(), text.style());
                    wrap(text.text(), width(text.style())).forEach(this::text);
                }
                case PrintLine.Rule rule -> {
                    select(Align.LEFT, Style.NORMAL);
                    text("-".repeat(columns));
                }
                case PrintLine.LeftRight leftRight -> {
                    select(Align.LEFT, leftRight.style());
                    leftRight(leftRight.left(), leftRight.right(), width(leftRight.style()));
                }
            }
        }

        /** The amount right-aligned at the width; when both do not fit, the left text first, wrapped. */
        private void leftRight(String left, String right, int width) {
            int gap = width - left.length() - right.length();
            if (gap >= 1) {
                text(left + " ".repeat(gap) + right);
                return;
            }
            wrap(left, width).forEach(this::text);
            text(" ".repeat(Math.max(0, width - right.length())) + right);
        }

        void select(Align newAlign, Style style) {
            boolean newBold = style == Style.BOLD;
            int newSize = switch (style) {
                case DOUBLE -> 0x11;
                case TALL -> 0x01;
                case NORMAL, BOLD -> 0;
            };
            if (newAlign != align) {
                out.writeBytes(new byte[] {ESC, 'a', (byte) newAlign.ordinal()});
                align = newAlign;
            }
            if (newBold != bold) {
                out.writeBytes(new byte[] {ESC, 'E', (byte) (newBold ? 1 : 0)});
                bold = newBold;
            }
            if (newSize != size) {
                out.writeBytes(new byte[] {GS, '!', (byte) newSize});
                size = newSize;
            }
        }

        private int width(Style style) {
            return style == Style.DOUBLE ? columns / 2 : columns;
        }

        private void text(String line) {
            out.writeBytes(codePage.encode(printable(line)));
            out.write(LF);
        }
    }

    /**
     * Text is data: a C0 (U+0000-U+001F) or C1 (U+007F-U+009F) control character becomes a space, so no ticket text
     * can carry an ESC, GS or DLE command to the printer. The QR payload is not text (it is length-framed).
     */
    static String printable(String text) {
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> printable.appendCodePoint(
                codePoint <= 0x1F || codePoint >= 0x7F && codePoint <= 0x9F ? ' ' : codePoint));
        return printable.toString();
    }

    /**
     * D155: a line that fits is kept as it is. A longer one breaks at the last space within the width, its
     * continuation indented to the text after "qty x " (or "   > "); a word longer than the width is cut hard.
     */
    static List<String> wrap(String text, int width) {
        if (text.length() <= width) {
            return List.of(text);
        }
        Matcher prefix = CONTINUATION_INDENT.matcher(text);
        int indent = prefix.lookingAt() && prefix.end() < width / 2 ? prefix.end() : 0;
        List<String> lines = new ArrayList<>();
        String rest = text;
        boolean first = true;
        while (true) {
            int available = first ? width : width - indent;
            String margin = first ? "" : " ".repeat(indent);
            if (rest.length() <= available) {
                lines.add(margin + rest);
                return lines;
            }
            int space = rest.lastIndexOf(' ', available);
            boolean wordBreak = space > (first ? indent : 0);
            String head = wordBreak ? rest.substring(0, space).stripTrailing() : rest.substring(0, available);
            rest = (wordBreak ? rest.substring(space + 1) : rest.substring(available)).stripLeading();
            lines.add(margin + head);
            if (rest.isEmpty()) {
                return lines;
            }
            first = false;
        }
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
