package com.xavier.backend.production.service;

import com.xavier.backend.orders.DeliverySlip;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.SubtotalLine;
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
import java.util.stream.Stream;

/** The printed text of production's own tickets (documents arrive laid out by fiscal). Pure functions. */
final class TicketLayouts {

    static final String RULE = "--------------------------------";
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
    static List<String> batch(String destination, long orderNumber, String tableCode, OrderMode mode, int batchNo,
                              Instant at, ZoneId zone, List<Line> lines) {
        List<String> out = new ArrayList<>(List.of(destination, header(orderNumber, tableCode, mode),
                "Ronda " + batchNo, time(at, zone), RULE));
        for (Line line : lines) {
            List<ComponentLine> components = componentsOf(line).stream()
                    .filter(component -> destination.equals(destinationOf(component.destination()))).toList();
            if (!destination.equals(destinationOf(line.destination())) && components.isEmpty()) {
                continue;
            }
            out.add(plain(line.quantity()) + " x " + item(line));
            if (line.notes() != null && !line.notes().isBlank()) {
                out.add("   > " + line.notes());
            }
            components.forEach(component -> out.add("   + " + plain(component.quantity()) + " x "
                    + component.description()));
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
    static List<String> deliverySlip(DeliverySlip slip, Instant at, ZoneId zone) {
        List<String> out = new ArrayList<>(List.of(DELIVERY_SLIP_TITLE, NOT_AN_INVOICE, DELIVERY + slip.orderNumber(),
                time(at, zone), RULE));
        addIfPresent(out, "Cliente: ", slip.customerName());
        addIfPresent(out, "Tel: ", slip.phone());
        DeliverySlip.Address address = slip.address();
        out.add(address.line1());
        addIfPresent(out, "", address.line2());
        String place = Stream.of(address.postalCode(), address.city()).filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
        if (!place.isEmpty()) {
            out.add(place);
        }
        addIfPresent(out, "Notas: ", address.notes());
        out.add("Zona: " + slip.zone());
        if (slip.promisedAt() != null) {
            out.add("Prometido: " + time(slip.promisedAt(), zone));
        }
        out.add(RULE);
        slip.lines().forEach(line -> out.add(plain(line.quantity()) + " x " + line.description()));
        out.add(RULE);
        out.add("A PAGAR " + slip.due().amount().toPlainString() + " " + slip.due().currency().getCurrencyCode());
        return out;
    }

    /**
     * A void for one destination, which the voided line or one of its menu components goes to: the line, and under it
     * the voided quantity of only the components that go there (D108), headed like its round (D129).
     */
    static List<String> voided(String destination, long orderNumber, String tableCode, OrderMode mode, Instant at,
                               ZoneId zone, Line line, BigDecimal quantity, String reason) {
        List<String> out = new ArrayList<>(List.of(VOID_TITLE, destination, header(orderNumber, tableCode, mode),
                time(at, zone), RULE, "-" + plain(quantity) + " x " + item(line)));
        componentsOf(line).stream().filter(component -> destination.equals(destinationOf(component.destination())))
                .forEach(component -> out.add("   + -" + plain(component.quantity()) + " x "
                        + component.description()));
        out.add("Motivo: " + reason);
        return out;
    }

    /** A bill to check (spec §8.1): it prints only, it is not a document. */
    static List<String> subtotal(long orderNumber, String tableCode, List<SubtotalLine> lines, Money due) {
        List<String> out = new ArrayList<>(List.of(SUBTOTAL_TITLE, NOT_AN_INVOICE, header(orderNumber, tableCode),
                RULE));
        for (SubtotalLine line : lines) {
            out.add(plain(line.netQty()) + " x " + line.description() + " " + line.unitPrice().amount().toPlainString()
                    + " = " + line.gross().amount().toPlainString());
        }
        out.add(RULE);
        out.add("TOTAL " + due.amount().toPlainString() + " " + due.currency().getCurrencyCode());
        return out;
    }

    static List<String> test(String printerCode, Instant at, ZoneId zone) {
        return List.of(TEST_TITLE, "Impressora " + printerCode, time(at, zone), "Acentos: ÁÉÍÓÚ ÂÊÔ ÃÕ Ç àç");
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

    private static void addIfPresent(List<String> out, String label, String value) {
        if (value != null) {
            out.add(label + value);
        }
    }

    private static String item(Line line) {
        return line.variantName() == null ? line.description() : line.description() + " (" + line.variantName() + ")";
    }

    private static String time(Instant at, ZoneId zone) {
        return PRINTED_TIME.format(at.atZone(zone));
    }

    private static String plain(BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
    }
}
