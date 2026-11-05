package com.xavier.backend.production.service;

import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The printed text of production's own tickets (documents arrive laid out by fiscal). Pure functions. */
final class TicketLayouts {

    static final String RULE = "--------------------------------";
    static final String VOID_TITLE = "*** ANULAÇÃO ***";
    static final String SUBTOTAL_TITLE = "SUBTOTAL";
    static final String NOT_AN_INVOICE = "NÃO SERVE DE FATURA";
    static final String TEST_TITLE = "TESTE DE IMPRESSÃO";

    private static final DateTimeFormatter PRINTED_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketLayouts() {
    }

    /** A round for one destination: only the lines of this round, so a second round prints only its new lines. */
    static List<String> batch(String destination, long orderNumber, String tableCode, int batchNo, Instant at,
                              ZoneId zone, List<Line> lines) {
        List<String> out = new ArrayList<>(List.of(destination, header(orderNumber, tableCode), "Ronda " + batchNo,
                time(at, zone), RULE));
        for (Line line : lines) {
            out.add(plain(line.quantity()) + " x " + item(line));
            if (line.notes() != null && !line.notes().isBlank()) {
                out.add("   > " + line.notes());
            }
        }
        return out;
    }

    static List<String> voided(String destination, long orderNumber, String tableCode, Instant at, ZoneId zone,
                               Line line, BigDecimal quantity, String reason) {
        return List.of(VOID_TITLE, destination, header(orderNumber, tableCode), time(at, zone), RULE,
                "-" + plain(quantity) + " x " + item(line), "Motivo: " + reason);
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

    private static String header(long orderNumber, String tableCode) {
        return tableCode == null ? "Pedido #" + orderNumber : "Mesa " + tableCode + " - Pedido #" + orderNumber;
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
