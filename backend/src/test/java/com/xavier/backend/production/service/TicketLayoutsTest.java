package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.DeliverySlip;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import com.xavier.backend.production.model.PrintLine.Text;
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
        List<String> lines = plain(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 2, AT, MAPUTO, List.of(
                line("Cerveja 2M", null, "2", null),
                line("Pizza", "Grande", "1.500", "sem\ncebola"))));

        assertThat(lines).containsExactly("BAR", "Mesa M1 - Pedido #42", "Ronda 2", "04/11/2026 12:05",
                TicketLayouts.RULE, "2 x Cerveja 2M", "1.5 x Pizza (Grande)", "   > sem\ncebola");
    }

    @Test
    void anOrderWithoutTableShowsOnlyItsNumber() {
        assertThat(plain(TicketLayouts.batch("BAR", 7, null, OrderMode.COUNTER, 1, AT, MAPUTO,
                List.of(line("Sopa", null, "1", null)))))
                .contains("Pedido #7").doesNotContain("Mesa null - Pedido #7");
    }

    @Test
    void aVoidTicketSaysSoWithTheVoidedQuantityAndReason() {
        assertThat(plain(TicketLayouts.voided("BAR", 42, "M1", OrderMode.DINE_IN, AT, MAPUTO, line("Cerveja 2M", null, "3", null),
                new BigDecimal("1"), "cliente desistiu")))
                .containsExactly(TicketLayouts.VOID_TITLE, "BAR", "Mesa M1 - Pedido #42", "04/11/2026 12:05",
                        TicketLayouts.RULE, "-1 x Cerveja 2M", "Motivo: cliente desistiu");
    }

    @Test
    void aSubtotalListsNetQuantitiesAndTheDueAndIsNoInvoice() {
        Currency mzn = Currency.getInstance("MZN");
        List<String> lines = plain(TicketLayouts.subtotal(42, "M1", List.of(new SubtotalLine("Cerveja 2M",
                new BigDecimal("2"), Money.of("58.00", mzn), Money.of("116.00", mzn))), Money.of("116.00", mzn)));

        assertThat(lines).containsExactly(TicketLayouts.SUBTOTAL_TITLE, TicketLayouts.NOT_AN_INVOICE,
                "Mesa M1 - Pedido #42", TicketLayouts.RULE, "2 x Cerveja 2M 58.00 = 116.00", TicketLayouts.RULE,
                "TOTAL 116.00 MZN");
    }

    @Test
    void aDeliveryOrTakeawayRoundIsHeadedByItsModeAndNumber() {
        assertThat(plain(TicketLayouts.batch("BAR", 12, null, OrderMode.DELIVERY, 1, AT, MAPUTO,
                List.of(line("Sopa", null, "1", null))))).containsSequence("BAR", "ENTREGA #12", "Ronda 1")
                .doesNotContain("Pedido #12");
        assertThat(plain(TicketLayouts.batch("BAR", 13, null, OrderMode.TAKEAWAY, 1, AT, MAPUTO,
                List.of(line("Sopa", null, "1", null))))).containsSequence("BAR", "TAKEAWAY #13", "Ronda 1");
    }

    @Test
    void aRoundWithoutModeKeepsTheR3Header() {
        assertThat(plain(TicketLayouts.batch("BAR", 42, "M1", null, 1, AT, MAPUTO, List.of(line("Sopa", null, "1", null)))))
                .containsSequence("BAR", "Mesa M1 - Pedido #42");
        assertThat(plain(TicketLayouts.batch("BAR", 7, null, null, 1, AT, MAPUTO, List.of(line("Sopa", null, "1", null)))))
                .containsSequence("BAR", "Pedido #7");
    }

    @Test
    void aMenuPrintsOnEachTicketWithOnlyThatDestinationsComponentsUnderIt() {
        Line menu = new Line(java.util.UUID.randomUUID(), "MN", "Menu almoço", null, new BigDecimal("2"), "rápido",
                null, List.of(new ComponentLine("S", "Sopa", new BigDecimal("2.000"), "KITCHEN"),
                new ComponentLine("J", "Sumo", new BigDecimal("2.000"), " BAR "),
                new ComponentLine("P", "Pão", new BigDecimal("2.000"), null)));
        Line beer = line("Cerveja 2M", null, "1", null);

        assertThat(plain(TicketLayouts.batch("KITCHEN", 42, "M1", OrderMode.DINE_IN, 1, AT, MAPUTO, List.of(menu, beer))))
                .endsWith(TicketLayouts.RULE, "2 x Menu almoço", "   > rápido", "   + 2 x Sopa");
        assertThat(plain(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 1, AT, MAPUTO, List.of(menu, beer))))
                .endsWith(TicketLayouts.RULE, "2 x Menu almoço", "   > rápido", "   + 2 x Sumo", "1 x Cerveja 2M");
    }

    @Test
    void aLineOfAnotherDestinationIsLeftOffTheTicket() {
        Line soup = new Line(java.util.UUID.randomUUID(), "S", "Sopa", null, BigDecimal.ONE, null, "KITCHEN");

        assertThat(plain(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 1, AT, MAPUTO,
                List.of(soup, line("Cerveja 2M", null, "1", null)))))
                .endsWith(TicketLayouts.RULE, "1 x Cerveja 2M").doesNotContain("1 x Sopa");
    }

    @Test
    void anR3LineWithoutComponentsPrintsAsBefore() {
        Line r3 = new Line(java.util.UUID.randomUUID(), "I1", "Cerveja 2M", null, BigDecimal.ONE, null, "BAR", null);

        assertThat(plain(TicketLayouts.batch("BAR", 42, "M1", null, 1, AT, MAPUTO, List.of(r3))))
                .endsWith(TicketLayouts.RULE, "1 x Cerveja 2M");
    }

    @Test
    void aDeliverySlipCarriesTheCustomerAddressZonePromiseLinesAndDue() {
        Currency mzn = Currency.getInstance("MZN");
        DeliverySlip slip = new DeliverySlip(12, "Ana Silva", "841234567", new DeliverySlip.Address(
                "Rua da Entrega 7", "3º esq", "Maputo", "1100", "portão azul"), "Z1",
                Instant.parse("2026-11-04T11:30:00Z"), List.of(new DeliverySlip.Line("Taxa de entrega",
                BigDecimal.ONE), new DeliverySlip.Line("Pizza", new BigDecimal("2.000"))),
                Money.of("15.50", mzn));

        assertThat(plain(TicketLayouts.deliverySlip(slip, AT, MAPUTO))).containsExactly(TicketLayouts.DELIVERY_SLIP_TITLE,
                TicketLayouts.NOT_AN_INVOICE, "ENTREGA #12", "04/11/2026 12:05", TicketLayouts.RULE,
                "Cliente: Ana Silva", "Tel: 841234567", "Rua da Entrega 7", "3º esq", "1100 Maputo",
                "Notas: portão azul", "Zona: Z1", "Prometido: 04/11/2026 13:30", TicketLayouts.RULE,
                "1 x Taxa de entrega", "2 x Pizza", TicketLayouts.RULE, "A PAGAR 15.50 MZN");
    }

    @Test
    void aDeliverySlipLeavesOutWhatTheDeliveryDoesNotHave() {
        DeliverySlip slip = new DeliverySlip(12, "Ana Silva", null, new DeliverySlip.Address("Rua da Entrega 7",
                null, "Maputo", null, null), "Z1", null, List.of(new DeliverySlip.Line("Pizza", BigDecimal.ONE)),
                Money.of("9.00", Currency.getInstance("MZN")));

        assertThat(plain(TicketLayouts.deliverySlip(slip, AT, MAPUTO))).containsExactly(TicketLayouts.DELIVERY_SLIP_TITLE,
                TicketLayouts.NOT_AN_INVOICE, "ENTREGA #12", "04/11/2026 12:05", TicketLayouts.RULE,
                "Cliente: Ana Silva", "Rua da Entrega 7", "Maputo", "Zona: Z1", TicketLayouts.RULE, "1 x Pizza",
                TicketLayouts.RULE, "A PAGAR 9.00 MZN");
    }

    @Test
    void anR3VoidWithoutModeOrComponentsPrintsAsBefore() {
        Line r3 = new Line(java.util.UUID.randomUUID(), "I1", "Cerveja 2M", null, new BigDecimal("3"), null, "BAR",
                null);

        assertThat(plain(TicketLayouts.voided("BAR", 42, "M1", null, AT, MAPUTO, r3, BigDecimal.ONE, "cliente desistiu")))
                .containsExactly(TicketLayouts.VOID_TITLE, "BAR", "Mesa M1 - Pedido #42", "04/11/2026 12:05",
                        TicketLayouts.RULE, "-1 x Cerveja 2M", "Motivo: cliente desistiu");
    }

    @Test
    void aVoidedMenuNamesOnEachStationOnlyItsComponentsThere() {
        Line menu = new Line(java.util.UUID.randomUUID(), "MN", "Menu almoço", null, new BigDecimal("2"), null,
                null, List.of(new ComponentLine("S", "Sopa", BigDecimal.ONE, "KITCHEN"),
                new ComponentLine("J", "Sumo", BigDecimal.ONE, "BAR")));

        assertThat(plain(TicketLayouts.voided("KITCHEN", 12, null, OrderMode.TAKEAWAY, AT, MAPUTO, menu, BigDecimal.ONE,
                "engano"))).containsExactly(TicketLayouts.VOID_TITLE, "KITCHEN", "TAKEAWAY #12", "04/11/2026 12:05",
                TicketLayouts.RULE, "-1 x Menu almoço", "   + -1 x Sopa", "Motivo: engano");
        assertThat(plain(TicketLayouts.voided("BAR", 12, null, OrderMode.DELIVERY, AT, MAPUTO, menu, BigDecimal.ONE,
                "engano"))).containsSequence("BAR", "ENTREGA #12").contains("   + -1 x Sumo")
                .doesNotContain("   + -1 x Sopa");
    }

    @Test
    void aMenuWithItsOwnDestinationPrintsThereWithItsComponentsOfThatDestination() {
        Line menu = new Line(java.util.UUID.randomUUID(), "MN", "Menu almoço", null, BigDecimal.ONE, null,
                "KITCHEN", List.of(new ComponentLine("S", "Sopa", BigDecimal.ONE, "KITCHEN"),
                new ComponentLine("J", "Sumo", BigDecimal.ONE, "BAR")));

        assertThat(TicketLayouts.destinations(List.of(menu))).containsExactly("KITCHEN", "BAR");
        assertThat(plain(TicketLayouts.batch("KITCHEN", 42, "M1", OrderMode.DINE_IN, 1, AT, MAPUTO, List.of(menu))))
                .endsWith(TicketLayouts.RULE, "1 x Menu almoço", "   + 1 x Sopa");
        assertThat(plain(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 1, AT, MAPUTO, List.of(menu))))
                .endsWith(TicketLayouts.RULE, "1 x Menu almoço", "   + 1 x Sumo");
    }

    @Test
    void aSlipWithAPostalCodeButNoCityPrintsThePostalCodeAlone() {
        DeliverySlip slip = new DeliverySlip(12, "Ana Silva", null, new DeliverySlip.Address("Rua da Entrega 7",
                null, null, "1100", null), "Z1", null, List.of(new DeliverySlip.Line("Pizza", BigDecimal.ONE)),
                Money.of("9.00", Currency.getInstance("MZN")));

        assertThat(plain(TicketLayouts.deliverySlip(slip, AT, MAPUTO))).containsSequence("Rua da Entrega 7", "1100",
                "Zona: Z1");
    }

    @Test
    void aSlipWithoutCityPostalCodeOrNameLeavesThoseLinesOut() {
        DeliverySlip slip = new DeliverySlip(12, null, null, new DeliverySlip.Address("Rua da Entrega 7", null, " ",
                null, null), "Z1", null, List.of(new DeliverySlip.Line("Pizza", BigDecimal.ONE)),
                Money.of("9.00", Currency.getInstance("MZN")));

        assertThat(plain(TicketLayouts.deliverySlip(slip, AT, MAPUTO))).containsSequence(TicketLayouts.RULE,
                "Rua da Entrega 7", "Zona: Z1").noneMatch(line -> line.startsWith("Cliente:"));
    }

    /** D155: kitchen tickets read at a distance; the text is R4's, only the style is new. */
    @Test
    void aRoundTitlesItsDestinationDoubleAndCentredHeadsInBoldAndPrintsItemsTall() {
        Line menu = new Line(java.util.UUID.randomUUID(), "MN", "Menu almoço", null, BigDecimal.ONE, "rápido",
                "BAR", List.of(new ComponentLine("J", "Sumo", BigDecimal.ONE, "BAR")));

        assertThat(TicketLayouts.batch("BAR", 42, "M1", OrderMode.DINE_IN, 2, AT, MAPUTO, List.of(menu)))
                .containsExactly(new Text("BAR", Style.DOUBLE, Align.CENTER),
                        new Text("Mesa M1 - Pedido #42", Style.BOLD, Align.LEFT), PrintLine.text("Ronda 2"),
                        PrintLine.text("04/11/2026 12:05"), new PrintLine.Rule(),
                        new Text("1 x Menu almoço", Style.TALL, Align.LEFT), PrintLine.text("   > rápido"),
                        PrintLine.text("   + 1 x Sumo"));
    }

    @Test
    void aVoidIsTitledDoubleAndCentredWithItsLineTall() {
        assertThat(TicketLayouts.voided("BAR", 42, "M1", OrderMode.DINE_IN, AT, MAPUTO,
                line("Cerveja 2M", null, "3", null), BigDecimal.ONE, "engano"))
                .containsExactly(new Text(TicketLayouts.VOID_TITLE, Style.DOUBLE, Align.CENTER),
                        new Text("BAR", Style.DOUBLE, Align.CENTER),
                        new Text("Mesa M1 - Pedido #42", Style.BOLD, Align.LEFT), PrintLine.text("04/11/2026 12:05"),
                        new PrintLine.Rule(), new Text("-1 x Cerveja 2M", Style.TALL, Align.LEFT),
                        PrintLine.text("Motivo: engano"));
    }

    @Test
    void aSubtotalAlignsItsAmountsRightAndItsTotalInBold() {
        Currency mzn = Currency.getInstance("MZN");

        assertThat(TicketLayouts.subtotal(42, "M1", List.of(new SubtotalLine("Cerveja 2M", new BigDecimal("2"),
                Money.of("58.00", mzn), Money.of("116.00", mzn))), Money.of("116.00", mzn)))
                .containsExactly(new Text(TicketLayouts.SUBTOTAL_TITLE, Style.DOUBLE, Align.CENTER),
                        new Text(TicketLayouts.NOT_AN_INVOICE, Style.NORMAL, Align.CENTER),
                        new Text("Mesa M1 - Pedido #42", Style.BOLD, Align.LEFT), new PrintLine.Rule(),
                        new PrintLine.LeftRight("2 x Cerveja 2M", "58.00 = 116.00", Style.NORMAL),
                        new PrintLine.Rule(), new PrintLine.LeftRight("TOTAL", "116.00 MZN", Style.BOLD));
    }

    @Test
    void aDeliverySlipIsTitledDoubleAndShowsTheDueInBold() {
        DeliverySlip slip = new DeliverySlip(12, null, null, new DeliverySlip.Address("Rua da Entrega 7", null, null,
                null, null), "Z1", null, List.of(new DeliverySlip.Line("Pizza", BigDecimal.ONE)),
                Money.of("9.00", Currency.getInstance("MZN")));

        List<PrintLine> lines = TicketLayouts.deliverySlip(slip, AT, MAPUTO);

        assertThat(lines).startsWith(new Text(TicketLayouts.DELIVERY_SLIP_TITLE, Style.DOUBLE, Align.CENTER),
                new Text(TicketLayouts.NOT_AN_INVOICE, Style.NORMAL, Align.CENTER),
                new Text("ENTREGA #12", Style.BOLD, Align.LEFT));
        assertThat(lines).last().isEqualTo(new PrintLine.LeftRight("A PAGAR", "9.00 MZN", Style.BOLD));
    }

    /** D156: the installer checks the table and the width at a glance. */
    @Test
    void theTestPageHasARulerOfTheColumnsTheAccentsAndEachStyle() {
        List<PrintLine> page = TicketLayouts.test("K1", AT, MAPUTO, 42);

        assertThat(page).contains(PrintLine.text("123456789012345678901234567890123456789012"),
                PrintLine.text("Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç ãõ"), new Text("Negrito", Style.BOLD, Align.LEFT),
                new Text("Duplo", Style.DOUBLE, Align.LEFT), new Text("Alto", Style.TALL, Align.LEFT));
        assertThat(page).first().isEqualTo(new Text(TicketLayouts.TEST_TITLE, Style.DOUBLE, Align.CENTER));
        assertThat(plain(page)).contains("Impressora K1", "04/11/2026 12:05");
        assertThat(plain(TicketLayouts.test("K1", AT, MAPUTO, 32))).contains("12345678901234567890123456789012");
    }

    static List<String> plain(List<PrintLine> lines) {
        return lines.stream().map(PrintLine::plainText).toList();
    }

    static Line line(String description, String variant, String quantity, String notes) {
        return new Line(java.util.UUID.randomUUID(), "I1", description, variant, new BigDecimal(quantity), notes,
                "BAR");
    }
}
