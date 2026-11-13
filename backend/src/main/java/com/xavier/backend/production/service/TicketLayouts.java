package com.xavier.backend.production.service;

import com.xavier.backend.orders.DeliverySlip;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.production.model.PrintLine;
import com.xavier.backend.production.model.PrintLine.Align;
import com.xavier.backend.production.model.PrintLine.Style;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * The printed lines of production's own tickets (documents arrive laid out by fiscal), styled per D155: titles DOUBLE
 * and centred, headers BOLD, item lines TALL, notes and components NORMAL, amounts aligned right. Their plain text is
 * R4's. Pure functions.
 */
final class TicketLayouts {

    /** A rule read as plain text (R4 width); printed, a rule is as wide as the printer (D155). */
    static final String RULE = "-".repeat(PrintLine.PLAIN_WIDTH);
    static final String VOID_TITLE = "*** ANULAÇÃO ***";
    static final String SUBTOTAL_TITLE = "SUBTOTAL";
    static final String NOT_AN_INVOICE = "NÃO SERVE DE FATURA";
    static final String TEST_TITLE = "TESTE DE IMPRESSÃO";
    static final String DELIVERY_SLIP_TITLE = "GUIA DE ENTREGA";

    private static final String DELIVERY = "ENTREGA #";
    private static final String TAKEAWAY = "TAKEAWAY #";

    private static final DateTimeFormatter PRINTED_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketLayouts() {
    }

    /**
     * A round for one destination: only the lines of this round, so a second round prints only its new lines. A line
     * prints when it, or one of its menu components, goes to {@code destination}; under it, only the components that
     * go there (D108). A DELIVERY or TAKEAWAY round says so in its header; a round published before R4 has no mode
     * and keeps the R3 header (D129).
     */
    static List<PrintLine> batch(String destination, long orderNumber, String tableCode, OrderMode mode, int batchNo,
                              Instant at, ZoneId zone, List<Line> lines) {
        List<PrintLine> out = new ArrayList<>(List.of(title(destination), bold(header(orderNumber, tableCode, mode)),
                text("Ronda " + batchNo), text(time(at, zone)), new PrintLine.Rule()));
        for (Line line : lines) {
            List<ComponentLine> components = componentsOf(line).stream()
                    .filter(component -> destination.equals(destinationOf(component.destination()))).toList();
            if (!destination.equals(destinationOf(line.destination())) && components.isEmpty()) {
                continue;
            }
            out.add(tall(plain(line.quantity()) + " x " + item(line)));
            if (line.notes() != null && !line.notes().isBlank()) {
                out.add(text("   > " + line.notes()));
            }
            components.forEach(component -> out.add(text("   + " + plain(component.quantity()) + " x "
                    + component.description())));
        }
        return out;
    }

    /** The destinations a round prints on, in the order its lines and their components name them. */
    static Set<String> destinations(List<Line> lines) {
        Set<String> destinations = new LinkedHashSet<>();
        for (Line line : lines) {
            Optional.ofNullable(destinationOf(line.destination())).ifPresent(destinations::add);
            componentsOf(line).forEach(component -> Optional.ofNullable(destinationOf(component.destination()))
                    .ifPresent(destinations::add));
        }
        return destinations;
    }

    /**
     * The courier's non-fiscal slip (D129): the customer, the address snapshot, the zone, the promise, the billable
     * lines and the due. What the delivery does not have (a phone, a second address line…) is left out.
     */
    static List<PrintLine> deliverySlip(DeliverySlip slip, Instant at, ZoneId zone) {
        List<PrintLine> out = new ArrayList<>(List.of(title(DELIVERY_SLIP_TITLE), centred(NOT_AN_INVOICE),
                bold(DELIVERY + slip.orderNumber()), text(time(at, zone)), new PrintLine.Rule()));
        addIfPresent(out, "Cliente: ", slip.customerName());
        addIfPresent(out, "Tel: ", slip.phone());
        DeliverySlip.Address address = slip.address();
        out.add(text(address.line1()));
        addIfPresent(out, "", address.line2());
        String place = Stream.of(address.postalCode(), address.city()).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        if (!place.isEmpty()) {
            out.add(text(place));
        }
        addIfPresent(out, "Notas: ", address.notes());
        out.add(text("Zona: " + slip.zone()));
        if (slip.promisedAt() != null) {
            out.add(text("Prometido: " + time(slip.promisedAt(), zone)));
        }
        out.add(new PrintLine.Rule());
        slip.lines().forEach(line -> out.add(text(plain(line.quantity()) + " x " + line.description())));
        out.add(new PrintLine.Rule());
        out.add(new PrintLine.LeftRight("A PAGAR", amount(slip.due()), Style.BOLD));
        return out;
    }

    /**
     * A void for one destination, which the voided line or one of its menu components goes to: the line, and under it
     * the voided quantity of only the components that go there (D108), headed like its round (D129).
     */
    static List<PrintLine> voided(String destination, long orderNumber, String tableCode, OrderMode mode, Instant at,
                               ZoneId zone, Line line, BigDecimal quantity, String reason) {
        List<PrintLine> out = new ArrayList<>(List.of(title(VOID_TITLE), title(destination),
                bold(header(orderNumber, tableCode, mode)), text(time(at, zone)), new PrintLine.Rule(),
                tall("-" + plain(quantity) + " x " + item(line))));
        componentsOf(line).stream().filter(component -> destination.equals(destinationOf(component.destination())))
                .forEach(component -> out.add(text("   + -" + plain(component.quantity()) + " x "
                        + component.description())));
        out.add(text("Motivo: " + reason));
        return out;
    }

    /** A bill to check (spec §8.1): it prints only, it is not a document. */
    static List<PrintLine> subtotal(long orderNumber, String tableCode, List<SubtotalLine> lines, Money due) {
        List<PrintLine> out = new ArrayList<>(List.of(title(SUBTOTAL_TITLE), centred(NOT_AN_INVOICE),
                bold(header(orderNumber, tableCode)), new PrintLine.Rule()));
        for (SubtotalLine line : lines) {
            out.add(new PrintLine.LeftRight(plain(line.netQty()) + " x " + line.description(),
                    line.unitPrice().amount().toPlainString() + " = " + line.gross().amount().toPlainString(),
                    Style.NORMAL));
        }
        out.add(new PrintLine.Rule());
        out.add(new PrintLine.LeftRight("TOTAL", amount(due), Style.BOLD));
        return out;
    }

    /**
     * D156: a ruler of {@code columns} digits (the last digit must be the paper's last character), every Portuguese
     * accent of the table, and one line in each style.
     */
    static List<PrintLine> test(String printerCode, Instant at, ZoneId zone, int columns) {
        String ruler = IntStream.rangeClosed(1, columns).mapToObj(column -> String.valueOf(column % 10))
                .collect(Collectors.joining());
        return List.of(title(TEST_TITLE), text("Impressora " + printerCode), text(time(at, zone)), text(ruler),
                text("Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç ãõ"), bold("Negrito"), PrintLine.text("Duplo", Style.DOUBLE),
                tall("Alto"));
    }

    private static String header(long orderNumber, String tableCode, OrderMode mode) {
        if (mode == OrderMode.DELIVERY) {
            return DELIVERY + orderNumber;
        }
        if (mode == OrderMode.TAKEAWAY) {
            return TAKEAWAY + orderNumber;
        }
        return header(orderNumber, tableCode);
    }

    private static String header(long orderNumber, String tableCode) {
        return tableCode == null ? "Pedido #" + orderNumber : "Mesa " + tableCode + " - Pedido #" + orderNumber;
    }

    /** A publication stored before R4 has no components. */
    private static List<ComponentLine> componentsOf(Line line) {
        return line.components() == null ? List.of() : line.components();
    }

    /** {@code null} when nothing prepares it (D56). */
    private static String destinationOf(String destination) {
        return destination == null || destination.isBlank() ? null : destination.strip();
    }

    private static void addIfPresent(List<PrintLine> out, String label, String value) {
        if (value != null) {
            out.add(text(label + value));
        }
    }

    private static String item(Line line) {
        return line.variantName() == null ? line.description() : line.description() + " (" + line.variantName() + ")";
    }

    private static String amount(Money money) {
        return money.amount().toPlainString() + " " + money.currency().getCurrencyCode();
    }

    private static PrintLine text(String text) {
        return PrintLine.text(text);
    }

    /** D155: a title (destination, void, subtotal, slip, test) reads from across the kitchen. */
    private static PrintLine title(String text) {
        return new PrintLine.Text(text, Style.DOUBLE, Align.CENTER);
    }

    private static PrintLine centred(String text) {
        return new PrintLine.Text(text, Style.NORMAL, Align.CENTER);
    }

    private static PrintLine bold(String text) {
        return PrintLine.text(text, Style.BOLD);
    }

    /** D155: an item line, readable at a distance. */
    private static PrintLine tall(String text) {
        return PrintLine.text(text, Style.TALL);
    }

    private static String time(Instant at, ZoneId zone) {
        return PRINTED_TIME.format(at.atZone(zone));
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
