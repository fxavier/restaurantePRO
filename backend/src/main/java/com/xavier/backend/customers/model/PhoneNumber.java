package com.xavier.backend.customers.model;

import java.util.regex.Pattern;

/**
 * A phone number normalized to its digits (D72): spaces, dashes, dots, brackets and a leading {@code +} are dropped,
 * so {@code +258 84-123 4567} and {@code 258841234567} are the same number. 4 to 20 digits, as the
 * {@code customer_phone_digits_ck} constraint.
 */
public record PhoneNumber(String digits) {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s+\\-().]");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{4,20}");

    public PhoneNumber {
        if (digits == null || !DIGITS.matcher(digits).matches()) {
            throw new IllegalArgumentException("A phone number has 4 to 20 digits");
        }
    }

    public static PhoneNumber of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("A phone number has 4 to 20 digits");
        }
        return new PhoneNumber(SEPARATORS.matcher(raw).replaceAll(""));
    }

    /** True when {@code raw} is written only with digits and phone separators. */
    static boolean looksLikeAPhone(String raw) {
        String digits = SEPARATORS.matcher(raw).replaceAll("");
        return !digits.isEmpty() && digits.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    static String digitsOf(String raw) {
        return SEPARATORS.matcher(raw).replaceAll("");
    }
}
