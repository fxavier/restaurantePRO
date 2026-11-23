package com.xavier.backend.production.model;

import com.xavier.backend.production.model.dto.TicketComponentView;
import com.xavier.backend.production.model.dto.TicketItemView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The items of a round ticket as its printout holds them (D155 layout, written by production's TicketLayouts): after
 * the header's rule, each item a TALL line {@code "q x name"}, its note {@code "   > note"} and its components
 * {@code "   + q x name"}. The ticket stores only its printout, so this is all it records; a printout that does not
 * read this way (a pre-D155 plain ticket, any other source) has no items, and its text lines still say everything.
 */
public final class RoundItems {

    public static final String QUANTITY_SEPARATOR = " x ";
    public static final String NOTE_PREFIX = "   > ";
    public static final String COMPONENT_PREFIX = "   + ";

    private RoundItems() {
    }

    public static List<TicketItemView> read(List<PrintLine> lines) {
        int rule = indexOfRule(lines);
        if (rule < 0) {
            return List.of();
        }
        List<Item> items = new ArrayList<>();
        for (PrintLine line : lines.subList(rule + 1, lines.size())) {
            if (!(line instanceof PrintLine.Text text) || !readInto(items, text)) {
                return List.of();
            }
        }
        return items.stream().map(Item::toView).toList();
    }

    private static boolean readInto(List<Item> items, PrintLine.Text line) {
        String text = line.text();
        if (line.style() == PrintLine.Style.TALL) {
            return counted(text).map(item -> items.add(new Item(item.quantity(), item.name()))).orElse(false);
        }
        if (items.isEmpty()) {
            return false;
        }
        Item last = items.getLast();
        if (text.startsWith(NOTE_PREFIX) && last.notes == null) {
            last.notes = text.substring(NOTE_PREFIX.length());
            return true;
        }
        if (text.startsWith(COMPONENT_PREFIX)) {
            return counted(text.substring(COMPONENT_PREFIX.length())).map(last.components::add).orElse(false);
        }
        return false;
    }

    /** {@code "1.5 x Pizza"}: the quantity prints first and has no separator in it, so the first one splits. */
    private static Optional<TicketComponentView> counted(String text) {
        int separator = text.indexOf(QUANTITY_SEPARATOR);
        if (separator <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TicketComponentView(new BigDecimal(text.substring(0, separator)),
                    text.substring(separator + QUANTITY_SEPARATOR.length())));
        } catch (NumberFormatException notAQuantity) {
            return Optional.empty();
        }
    }

    private static int indexOfRule(List<PrintLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) instanceof PrintLine.Rule) {
                return i;
            }
        }
        return -1;
    }

    private static final class Item {

        private final BigDecimal quantity;
        private final String name;
        private String notes;
        private final List<TicketComponentView> components = new ArrayList<>();

        private Item(BigDecimal quantity, String name) {
            this.quantity = quantity;
            this.name = name;
        }

        private TicketItemView toView() {
            return new TicketItemView(quantity, name, notes, components);
        }
    }
}
