package com.xavier.backend.customers.model;

/**
 * What a search text means (D72). Written only with digits and phone separators, it is a phone suffix of at least
 * {@value #MIN_DIGITS} digits; anything else is part of a name, of at least {@value #MIN_NAME_LENGTH} characters.
 * Either way an exact tax id also matches. The search returns candidates, never a selection (spec §5).
 */
public record CustomerQuery(Kind kind, String text) {

    public static final int MIN_DIGITS = 4;
    public static final int MIN_NAME_LENGTH = 3;

    public enum Kind { PHONE_SUFFIX, NAME }

    public static boolean isValid(String raw) {
        return parse(raw) != null;
    }

    /** @throws IllegalArgumentException when {@code raw} is too short to search (validated at the boundary) */
    public static CustomerQuery of(String raw) {
        CustomerQuery query = parse(raw);
        if (query == null) {
            throw new IllegalArgumentException("Search with at least " + MIN_DIGITS + " digits or "
                    + MIN_NAME_LENGTH + " characters");
        }
        return query;
    }

    private static CustomerQuery parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (PhoneNumber.looksLikeAPhone(text)) {
            String digits = PhoneNumber.digitsOf(text);
            return digits.length() >= MIN_DIGITS ? new CustomerQuery(Kind.PHONE_SUFFIX, digits) : null;
        }
        return text.codePointCount(0, text.length()) >= MIN_NAME_LENGTH ? new CustomerQuery(Kind.NAME, text) : null;
    }
}
