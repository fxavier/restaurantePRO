package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the kitchen reads: the destination, the order and table, the round, and only the lines given. */
class TicketLayoutsTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");
    static final Instant AT = Instant.parse("2026-11-04T10:05:00Z");

    @Test
    void aRoundTicketNamesDestinationTableOrderRoundAndTimeThenItsLines() {
        List<String> lines = TicketLayouts.batch("BAR", 42, "M1", 2, AT, MAPUTO, List.of(
                line("Cerveja 2M", null, "2", null),
                line("Pizza", "Grande", "1.500", "sem\ncebola")));

        assertThat(lines).containsExactly("BAR", "Mesa M1 - Pedido #42", "Ronda 2", "04/11/2026 12:05",
                TicketLayouts.RULE, "2 x Cerveja 2M", "1.5 x Pizza (Grande)", "   > sem\ncebola");
    }

    @Test
    void anOrderWithoutTableShowsOnlyItsNumber() {
        assertThat(TicketLayouts.batch("COZINHA", 7, null, 1, AT, MAPUTO, List.of(line("Sopa", null, "1", null))))
                .contains("Pedido #7").doesNotContain("Mesa null - Pedido #7");
    }

    @Test
    void aVoidTicketSaysSoWithTheVoidedQuantityAndReason() {
        assertThat(TicketLayouts.voided("BAR", 42, "M1", AT, MAPUTO, line("Cerveja 2M", null, "3", null),
                new BigDecimal("1"), "cliente desistiu"))
                .containsExactly(TicketLayouts.VOID_TITLE, "BAR", "Mesa M1 - Pedido #42", "04/11/2026 12:05",
                        TicketLayouts.RULE, "-1 x Cerveja 2M", "Motivo: cliente desistiu");
    }

    @Test
    void aSubtotalListsNetQuantitiesAndTheDueAndIsNoInvoice() {
        Currency mzn = Currency.getInstance("MZN");
        List<String> lines = TicketLayouts.subtotal(42, "M1", List.of(new SubtotalLine("Cerveja 2M",
                new BigDecimal("2"), Money.of("58.00", mzn), Money.of("116.00", mzn))), Money.of("116.00", mzn));

        assertThat(lines).containsExactly(TicketLayouts.SUBTOTAL_TITLE, TicketLayouts.NOT_AN_INVOICE,
                "Mesa M1 - Pedido #42", TicketLayouts.RULE, "2 x Cerveja 2M 58.00 = 116.00", TicketLayouts.RULE,
                "TOTAL 116.00 MZN");
    }

    static Line line(String description, String variant, String quantity, String notes) {
        return new Line(java.util.UUID.randomUUID(), "I1", description, variant, new BigDecimal(quantity), notes,
                "BAR");
    }
}
