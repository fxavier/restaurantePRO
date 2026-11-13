package com.xavier.backend.production.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D155: STYLED_V1 is stored as JSON lines {@code {"t","s","a"}}, {@code {"rule": true}}, {@code {"l","r","s"}} and
 * reads back as the same lines; a ticket reads back the printout it was created with, in either format.
 */
class StyledContentTest {

    static final List<PrintLine> LINES = List.of(new PrintLine.Text("BAR", Style.DOUBLE, Align.CENTER),
            new PrintLine.Rule(), new PrintLine.Text("2 x Cerveja", Style.TALL, Align.RIGHT),
            new PrintLine.LeftRight("TOTAL", "116.00 MZN", Style.BOLD), PrintLine.text("Ação \"já\""));

    @Test
    void theStoredFormIsTheD155JsonShape() {
        assertThat(StyledContent.toJson(LINES)).isEqualTo("""
                [{"t":"BAR","s":"DOUBLE","a":"CENTER"},{"rule":true},{"t":"2 x Cerveja","s":"TALL","a":"RIGHT"},\
                {"l":"TOTAL","r":"116.00 MZN","s":"BOLD"},{"t":"Ação \\"já\\"","s":"NORMAL","a":"LEFT"}]""");
    }

    @Test
    void everyLineReadsBackAsItWasWritten() {
        assertThat(StyledContent.fromJson(StyledContent.toJson(LINES))).isEqualTo(LINES);
    }

    @Test
    void aStyledTicketReadsBackItsLinesAndAPlainOneItsTextAndQr() {
        ProductionTicket styled = ProductionTicket.unrouted(draft(Printout.styled(LINES)), java.time.Instant.EPOCH);
        ProductionTicket plain = ProductionTicket.unrouted(draft(Printout.plain(List.of("FT A/1", "TOTAL"),
                "https://q")), java.time.Instant.EPOCH);

        assertThat(styled.printout()).isEqualTo(new Printout(ContentFormat.STYLED_V1, LINES, null));
        assertThat(styled.lines()).containsExactly("BAR", "-".repeat(32), "2 x Cerveja", "TOTAL 116.00 MZN",
                "Ação \"já\"");
        assertThat(plain.printout()).isEqualTo(Printout.plain(List.of("FT A/1", "TOTAL"), "https://q"));
    }

    @Test
    void aLineBreakInsideAStyledTextBecomesASpace() {
        TicketDraft draft = draft(Printout.styled(List.of(PrintLine.text("sem\ncebola", Style.BOLD),
                new PrintLine.LeftRight("a\r\nb", "c\nd", Style.NORMAL))));

        assertThat(draft.printout().lines()).containsExactly(PrintLine.text("sem cebola", Style.BOLD),
                new PrintLine.LeftRight("a b", "c d", Style.NORMAL));
    }

    private static TicketDraft draft(Printout printout) {
        return new TicketDraft(UUID.randomUUID(), TicketSource.BATCH, UUID.randomUUID(), "BAR", JobKind.ORIGINAL,
                null, null, printout);
    }
}
