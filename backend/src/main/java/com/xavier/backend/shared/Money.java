package com.xavier.backend.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * An amount of money in one currency (D15, spec §8.4).
 *
 * <p>The amount always has exactly the currency's minor-unit scale (2 for MZN/EUR). Construction never
 * rounds: an amount with more precision is rejected. The only rounding happens in {@link #times} and
 * {@link #divide} (HALF_UP, line level, D59). Splitting uses {@code allocate}, whose parts always sum to the total.
 * Combining two currencies is rejected (single currency per installation, P2).
 */
public record Money(BigDecimal amount, Currency currency) {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        try {
            amount = amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    amount + " has more decimals than " + currency.getCurrencyCode() + " allows", e);
        }
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /** Line-level multiplication (price x quantity), rounded HALF_UP to the currency minor unit. */
    public Money times(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity");
        return new Money(amount.multiply(quantity).setScale(currency.getDefaultFractionDigits(), ROUNDING), currency);
    }

    /**
     * Division rounded HALF_UP to the currency minor unit, e.g. the net of a tax-inclusive gross,
     * {@code gross.divide(1 + rate/100)} (D59). With {@link #times} it is the only rounding point.
     *
     * @throws IllegalArgumentException for a zero divisor
     */
    public Money divide(BigDecimal divisor) {
        Objects.requireNonNull(divisor, "divisor");
        if (divisor.signum() == 0) {
            throw new IllegalArgumentException("Cannot divide " + this + " by zero");
        }
        return new Money(amount.divide(divisor, currency.getDefaultFractionDigits(), ROUNDING), currency);
    }

    /** Splits into {@code parts} equal shares; leftover minor units go to the first shares. */
    public List<Money> allocate(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1, was " + parts);
        }
        return largestRemainder(Collections.nCopies(parts, BigDecimal.ONE));
    }

    /** Splits proportionally to non-negative {@code weights} (at least one positive). */
    public List<Money> allocate(List<BigDecimal> weights) {
        Objects.requireNonNull(weights, "weights");
        if (weights.isEmpty() || weights.stream().anyMatch(w -> w.signum() < 0)
                || weights.stream().noneMatch(w -> w.signum() > 0)) {
            throw new IllegalArgumentException("weights must be non-negative with at least one positive: " + weights);
        }
        return largestRemainder(weights);
    }

    /**
     * Largest-remainder allocation in exact minor units: each share gets the floor of its exact
     * proportion, then the leftover minor units go one by one to the largest remainders (ties: earlier
     * share first). The shares always sum to this amount.
     */
    private List<Money> largestRemainder(List<BigDecimal> weights) {
        int scale = currency.getDefaultFractionDigits();
        BigDecimal units = amount.movePointRight(scale).abs();
        BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        int n = weights.size();
        BigDecimal[] shares = new BigDecimal[n];
        BigDecimal[] remainders = new BigDecimal[n];
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal numerator = units.multiply(weights.get(i));
            shares[i] = numerator.divideToIntegralValue(weightSum);
            remainders[i] = numerator.subtract(shares[i].multiply(weightSum));
            allocated = allocated.add(shares[i]);
        }

        int leftover = units.subtract(allocated).intValueExact();
        IntStream.range(0, n).boxed()
                .sorted(Comparator.comparing((Integer i) -> remainders[i]).reversed().thenComparing(i -> i))
                .limit(leftover)
                .forEach(i -> shares[i] = shares[i].add(BigDecimal.ONE));

        int sign = amount.signum();
        List<Money> result = new ArrayList<>(n);
        for (BigDecimal share : shares) {
            BigDecimal signed = sign < 0 ? share.negate() : share;
            result.add(new Money(signed.movePointLeft(scale), currency));
        }
        return List.copyOf(result);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine " + currency.getCurrencyCode() + " with " + other.currency.getCurrencyCode());
        }
    }
}
