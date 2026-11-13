package com.xavier.backend.production.model;

import java.util.Objects;

/**
 * One line of a styled printout (D155), laid out at send time for the printer that prints it: a text with a style
 * and an alignment, a rule as wide as the printer, or a left text with an amount aligned right.
 */
public sealed interface PrintLine {

    /** The rule's width, and the layout width, of a printout read as plain text (D57's 32-column layouts). */
    int PLAIN_WIDTH = 32;

    enum Style {
        NORMAL, BOLD,
        /** Double width and height: half the columns. */
        DOUBLE,
        /** Double height only. */
        TALL
    }

    enum Align { LEFT, CENTER, RIGHT }

    /** The line as plain text (what a ticket view lists, and what the R2 subtotal key digests). */
    String plainText();

    /** The same line with every line break inside a text turned into a space. */
    PrintLine withoutLineBreaks();

    static Text text(String text) {
        return new Text(text, Style.NORMAL, Align.LEFT);
    }

    static Text text(String text, Style style) {
        return new Text(text, style, Align.LEFT);
    }

    record Text(String text, Style style, Align align) implements PrintLine {

        public Text {
            text = text == null ? "" : text;
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(align, "align");
        }

        @Override
        public String plainText() {
            return text;
        }

        @Override
        public Text withoutLineBreaks() {
            return new Text(oneLine(text), style, align);
        }
    }

    record Rule() implements PrintLine {

        @Override
        public String plainText() {
            return "-".repeat(PLAIN_WIDTH);
        }

        @Override
        public Rule withoutLineBreaks() {
            return this;
        }
    }

    record LeftRight(String left, String right, Style style) implements PrintLine {

        public LeftRight {
            left = left == null ? "" : left;
            right = right == null ? "" : right;
            Objects.requireNonNull(style, "style");
        }

        @Override
        public String plainText() {
            return left + " " + right;
        }

        @Override
        public LeftRight withoutLineBreaks() {
            return new LeftRight(oneLine(left), oneLine(right), style);
        }
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ");
    }
}
