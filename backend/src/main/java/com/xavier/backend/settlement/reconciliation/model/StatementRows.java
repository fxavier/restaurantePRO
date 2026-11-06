package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.settlement.reconciliation.StatementLine;
import com.xavier.backend.settlement.reconciliation.model.dto.StatementError;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The one canonical statement format (D78, P37), and the checks every statement line passes, from a CSV file or from a
 * provider's {@code StatementSource}. The CSV is UTF-8 with the header {@value #HEADER}; blank lines are skipped and
 * a line is numbered by its physical line in the file (the header is line 1); a provider's lines from 1; {@code occurred_at} is ISO-8601 with an offset; a field may be double-quoted (a
 * quote inside it doubled). A line is refused when it is not of the period {@code [from, to)}, its amount is not
 * a positive plain decimal in the currency's minor units, or its fee is negative. At most {@link #MAX_LINES} lines;
 * at most {@link #MAX_ERRORS} errors are reported.
 */
public final class StatementRows {

    public static final String HEADER = "occurred_at,amount,fee,reference,authorization_code,line_ref";
    private static final int COLUMNS = 6;
    private static final int MAX_REFERENCE = 100;
    private static final int MAX_AUTHORIZATION = 50;
    /** A statement has at most this many lines, and at most this many errors are reported. */
    public static final int MAX_LINES = 50_000;
    public static final int MAX_ERRORS = 100;
    /**
     * Amounts are plain decimals only: an exponent ({@code 1E+100000000}) would make the scale check allocate
     * and spin for seconds per line.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^\\d{1,15}(\\.\\d{1,4})?$");
    private static final int MAX_INTEGER_DIGITS = 15;
    private static final int MAX_FRACTION_DIGITS = 4;

    private StatementRows() {
    }

    /** The rows that passed and every error; a statement is imported only without errors. */
    public record Result(List<StatementRow> rows, List<StatementError> errors) {
    }

    public static Result fromCsv(byte[] file, Currency currency, Instant from, Instant to) {
        String text = new String(file, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<String> lines = text.lines().toList();
        int header = 0;
        while (header < lines.size() && lines.get(header).isBlank()) {
            header++;
        }
        if (header == lines.size() || !HEADER.equalsIgnoreCase(lines.get(header).strip())) {
            return new Result(List.of(), List.of(new StatementError(Math.min(header, lines.size() - 1) + 1, "header",
                    "The first line must be " + HEADER)));
        }
        if (lines.subList(header + 1, lines.size()).stream().filter(line -> !line.isBlank()).count() > MAX_LINES) {
            return tooLong();
        }
        Checker checker = new Checker(currency, from, to);
        for (int i = header + 1; i < lines.size() && !checker.full(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            int lineNo = i + 1; // the physical line of the file, the header counting as one
            List<String> fields = split(lines.get(i));
            if (fields == null || fields.size() != COLUMNS) {
                checker.error(new StatementError(lineNo, "line", "A line has " + COLUMNS + " comma-separated "
                        + "columns"));
                continue;
            }
            checker.check(lineNo, fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(4),
                    fields.get(5));
        }
        return checker.result();
    }

    public static Result fromLines(List<StatementLine> lines, Currency currency, Instant from, Instant to) {
        if (lines.size() > MAX_LINES) {
            return tooLong();
        }
        Checker checker = new Checker(currency, from, to);
        for (int i = 0; i < lines.size() && !checker.full(); i++) {
            StatementLine line = lines.get(i);
            checker.check(i + 1, line.occurredAt() == null ? null : line.occurredAt().toString(),
                    plain(line.amount()), plain(line.fee()), line.reference(), line.authorizationCode(),
                    line.lineRef());
        }
        return checker.result();
    }

    /** The provider's lines as the canonical CSV: what a fetched batch's digest is computed over. */
    public static byte[] canonicalCsv(List<StatementLine> lines) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (StatementLine line : lines) {
            csv.append(line.occurredAt()).append(',').append(plain(line.amount())).append(',')
                    .append(plain(line.fee())).append(',').append(quoted(line.reference())).append(',')
                    .append(quoted(line.authorizationCode())).append(',').append(quoted(line.lineRef()))
                    .append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Result tooLong() {
        return new Result(List.of(), List.of(new StatementError(0, "file", "A statement has at most " + MAX_LINES
                + " lines: split it by period")));
    }

    private static final class Checker {

        private final Currency currency;
        private final Instant from;
        private final Instant to;
        private final List<StatementRow> rows = new ArrayList<>();
        private final List<StatementError> errors = new ArrayList<>();

        Checker(Currency currency, Instant from, Instant to) {
            this.currency = currency;
            this.from = from;
            this.to = to;
        }

        void check(int lineNo, String occurredAt, String amount, String fee, String reference,
                   String authorizationCode, String lineRef) {
            int before = errors.size();
            Instant at = instant(lineNo, occurredAt);
            Money paid = money(lineNo, "amount", amount, false);
            Money kept = fee == null || fee.isBlank() ? Money.of(BigDecimal.ZERO, currency)
                    : money(lineNo, "fee", fee, true);
            String ref = text(lineNo, "reference", reference, MAX_REFERENCE);
            String code = text(lineNo, "authorization_code", authorizationCode, MAX_AUTHORIZATION);
            String own = text(lineNo, "line_ref", lineRef, MAX_REFERENCE);
            if (errors.size() == before) {
                rows.add(new StatementRow(lineNo, at, paid, kept, ref, code, own));
            }
        }

        private Instant instant(int lineNo, String value) {
            Instant at;
            try {
                at = value == null ? null : OffsetDateTime.parse(value.strip()).toInstant();
            } catch (DateTimeParseException e) {
                at = null;
            }
            if (at == null) {
                error(new StatementError(lineNo, "occurred_at", "An ISO-8601 instant with an offset, e.g. "
                        + "2026-11-05T10:15:00+02:00"));
            } else if (at.isBefore(from) || !at.isBefore(to)) {
                error(new StatementError(lineNo, "occurred_at", "The line is not of the statement's period"));
            }
            return at;
        }

        private Money money(int lineNo, String field, String value, boolean zeroAllowed) {
            if (value == null || !PLAIN_DECIMAL.matcher(value.strip()).matches()) {
                error(new StatementError(lineNo, field, "A plain decimal number with at most "
                        + MAX_INTEGER_DIGITS + " digits and " + MAX_FRACTION_DIGITS + " decimals, e.g. 25.00"));
                return null;
            }
            BigDecimal number = new BigDecimal(value.strip());
            if (number.signum() < 0 || (number.signum() == 0 && !zeroAllowed)) {
                error(new StatementError(lineNo, field, zeroAllowed ? "Zero or more" : "More than zero"));
                return null;
            }
            try {
                return Money.of(number, currency);
            } catch (IllegalArgumentException e) {
                error(new StatementError(lineNo, field, "At most " + currency.getDefaultFractionDigits()
                        + " decimals in " + currency.getCurrencyCode()));
                return null;
            }
        }

        private String text(int lineNo, String field, String value, int max) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String text = value.strip();
            if (text.length() > max) {
                error(new StatementError(lineNo, field, "At most " + max + " characters"));
                return null;
            }
            return text;
        }

        void error(StatementError error) {
            if (!full()) {
                errors.add(error);
            }
        }

        boolean full() {
            return errors.size() >= MAX_ERRORS;
        }

        Result result() {
            return new Result(List.copyOf(rows), List.copyOf(errors));
        }
    }

    /** The fields of one CSV line, or {@code null} for an unterminated quote. */
    private static List<String> split(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                field.append('"');
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        fields.add(field.toString());
        return fields;
    }

    /** A provider's number as plain text; one too large or too precise keeps its short form, which is refused. */
    private static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        boolean bounded = value.scale() <= MAX_FRACTION_DIGITS && value.precision() - value.scale() <= MAX_INTEGER_DIGITS;
        return bounded ? value.toPlainString() : value.toString();
    }

    private static String quoted(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
}
