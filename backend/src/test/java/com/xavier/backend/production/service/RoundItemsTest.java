package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.Printout;
import com.xavier.backend.production.model.RoundItems;
import com.xavier.backend.production.model.dto.TicketComponentView;
import com.xavier.backend.production.model.dto.TicketItemView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A round ticket read back as the kitchen display uses it: each printed item with its quantity, its note and the
 * menu components that go to the ticket's station, exactly as {@link TicketLayouts#batch} wrote them (and stored
 * them, line breaks removed).
 */
class RoundItemsTest {

    static final Instant AT = Instant.parse("2026-11-04T10:05:00Z");
    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");

    @Test
    void aRoundReadsBackItsItemsQuantitiesNotesAndComponents() {
        Line beer = new Line(UUID.randomUUID(), "C", "Cerveja 2M", null, new BigDecimal("2"), null, "BAR");
        Line pizza = new Line(UUID.randomUUID(), "P", "Pizza x Queijo", "Grande", new BigDecimal("1.500"),
                "sem\ncebola", "BAR");
        Line menu = new Line(UUID.randomUUID(), "MN", "Menu almoço", null, BigDecimal.ONE, null, null,
                List.of(new ComponentLine("J", "Sumo", new BigDecimal("2.000"), "BAR"),
                        new ComponentLine("S", "Sopa", BigDecimal.ONE, "KITCHEN")));

        assertThat(RoundItems.read(stored(List.of(beer, pizza, menu)))).containsExactly(
                new TicketItemView(new BigDecimal("2"), "Cerveja 2M", null, List.of()),
                new TicketItemView(new BigDecimal("1.5"), "Pizza x Queijo (Grande)", "sem cebola", List.of()),
                new TicketItemView(BigDecimal.ONE, "Menu almoço", null,
                        List.of(new TicketComponentView(new BigDecimal("2"), "Sumo"))));
    }

    @Test
    void contentThatIsNotARoundLayoutReadsAsNoItems() {
        assertThat(RoundItems.read(List.of(PrintLine.text("grill")))).isEmpty();
        assertThat(RoundItems.read(List.of(new PrintLine.Rule(), PrintLine.text("Motivo: x")))).isEmpty();
        assertThat(RoundItems.read(List.of(new PrintLine.Rule(), PrintLine.text("Pizza", PrintLine.Style.TALL))))
                .isEmpty();
    }

    private static List<PrintLine> stored(List<Line> lines) {
        return Printout.styled(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 2, AT, MAPUTO, lines))
                .withoutLineBreaks().lines();
    }
}
