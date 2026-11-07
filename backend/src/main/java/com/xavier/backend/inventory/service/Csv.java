package com.xavier.backend.inventory.service;

/** CSV fields for the report exports (RFC 4180). */
final class Csv {

    private Csv() {
    }

    /**
     * RFC 4180: quoted when it holds a comma, a quote or a line break; a text a spreadsheet would read as a formula
     * (a leading = + - @, tab or carriage return) is defused with a leading apostrophe. Numbers stay as they are.
     */
    static String field(String value) {
        String text = value == null ? "" : value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !text.matches("-?[0-9.]+")) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
