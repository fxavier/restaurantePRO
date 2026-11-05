package com.xavier.backend.settlement.reconciliation.model;

import com.xavier.backend.settlement.model.PaymentMethodKind;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * D78's deterministic matching of a statement against the payments of its store and method; a pure function.
 *
 * <ol>
 *   <li>A line with a key is matched by it: an INTEGRATED method's {@code reference} (our attempt reference or the
 *       provider's transaction reference), an EXTERNAL_TERMINAL method's {@code authorization_code} and amount (the
 *       code alone gives AMOUNT_MISMATCH when only one payment has it). A key that finds no single payment is
 *       MISSING_INTERNAL: it never falls back to the amount.</li>
 *   <li>A line without a key is matched by amount within ±{@link #WINDOW}, against the payments the keys did not
 *       take, <b>only when exactly one payment is a candidate, and that payment is the candidate of this line
 *       alone</b>. Otherwise it is MISSING_INTERNAL: two equal card payments in the same minute are never paired at
 *       random.</li>
 *   <li>A line whose one payment is already matched, by an earlier batch or an earlier line of this one, is
 *       DUPLICATE_LINE.</li>
 *   <li>Every payment of {@code [from, to)} with money left that nothing matched, now or earlier, is
 *       MISSING_PROVIDER.</li>
 * </ol>
 * Lines are taken in line order whatever the order given, so the outcome is the same for the same statement.
 */
public final class StatementMatcher {

    public static final Duration WINDOW = Duration.ofMinutes(10);

    private StatementMatcher() {
    }

    /** One outcome per row, in line order, then the MISSING_PROVIDER payments, oldest first. */
    public static List<Match> match(PaymentMethodKind methodKind, List<StatementRow> rows,
                                    Collection<PaymentCandidate> payments, Instant from, Instant to) {
        List<StatementRow> lines = rows.stream().sorted(Comparator.comparingInt(StatementRow::lineNo)).toList();
        Map<Integer, Match> outcomes = new TreeMap<>();
        Set<UUID> taken = new HashSet<>();
        for (StatementRow row : lines) {
            if (keyOf(methodKind, row) != null) {
                outcomes.put(row.lineNo(), byKey(methodKind, row, payments, taken));
            }
        }
        Map<UUID, List<StatementRow>> picks = new HashMap<>();
        Map<UUID, PaymentCandidate> picked = new HashMap<>();
        for (StatementRow row : lines) {
            if (keyOf(methodKind, row) != null) {
                continue;
            }
            List<PaymentCandidate> candidates = payments.stream()
                    .filter(p -> !taken.contains(p.paymentId()) && p.net().equals(row.amount())
                            && !Duration.between(p.confirmedAt(), row.occurredAt()).abs().minus(WINDOW).isPositive())
                    .toList();
            if (candidates.size() == 1) {
                PaymentCandidate only = candidates.getFirst();
                picks.computeIfAbsent(only.paymentId(), id -> new ArrayList<>()).add(row);
                picked.put(only.paymentId(), only);
            } else {
                outcomes.put(row.lineNo(), new Match(EntryKind.MISSING_INTERNAL, row, null));
            }
        }
        picks.forEach((paymentId, byRows) -> {
            PaymentCandidate payment = picked.get(paymentId);
            for (StatementRow row : byRows) {
                if (payment.matchedEarlier()) {
                    outcomes.put(row.lineNo(), new Match(EntryKind.DUPLICATE_LINE, row, payment));
                } else if (byRows.size() == 1) {
                    taken.add(paymentId);
                    outcomes.put(row.lineNo(), new Match(EntryKind.MATCHED, row, payment));
                } else {
                    outcomes.put(row.lineNo(), new Match(EntryKind.MISSING_INTERNAL, row, null));
                }
            }
        });
        List<Match> result = new ArrayList<>(outcomes.values());
        payments.stream()
                .filter(p -> !p.matchedEarlier() && !taken.contains(p.paymentId()) && p.net().amount().signum() > 0
                        && !p.confirmedAt().isBefore(from) && p.confirmedAt().isBefore(to))
                .sorted(Comparator.comparing(PaymentCandidate::confirmedAt).thenComparing(PaymentCandidate::paymentId))
                .forEach(p -> result.add(new Match(EntryKind.MISSING_PROVIDER, null, p)));
        return List.copyOf(result);
    }

    private static Match byKey(PaymentMethodKind methodKind, StatementRow row, Collection<PaymentCandidate> payments,
                               Set<UUID> taken) {
        PaymentCandidate found = null;
        EntryKind kind = EntryKind.MATCHED;
        if (methodKind == PaymentMethodKind.INTEGRATED) {
            List<PaymentCandidate> byReference = only(payments, p -> row.reference().equals(p.providerReference())
                    || row.reference().equals(p.providerTransactionRef()));
            if (byReference.size() == 1) {
                found = byReference.getFirst();
                kind = found.net().equals(row.amount()) ? EntryKind.MATCHED : EntryKind.AMOUNT_MISMATCH;
            }
        } else {
            List<PaymentCandidate> byCode = only(payments,
                    p -> row.authorizationCode().equals(p.authorizationCode()));
            List<PaymentCandidate> exact = only(byCode, p -> p.net().equals(row.amount()));
            if (exact.size() == 1) {
                found = exact.getFirst();
            } else if (exact.isEmpty() && byCode.size() == 1) {
                found = byCode.getFirst();
                kind = EntryKind.AMOUNT_MISMATCH;
            }
        }
        if (found == null) {
            return new Match(EntryKind.MISSING_INTERNAL, row, null);
        }
        if (found.matchedEarlier() || !taken.add(found.paymentId())) {
            return new Match(EntryKind.DUPLICATE_LINE, row, found);
        }
        return new Match(kind, row, found);
    }

    /** The key a line is matched by for this method, or {@code null}: it is matched by amount and time. */
    private static String keyOf(PaymentMethodKind methodKind, StatementRow row) {
        return switch (methodKind) {
            case INTEGRATED -> row.reference();
            case EXTERNAL_TERMINAL -> row.authorizationCode();
            case CASH -> null;
        };
    }

    private static List<PaymentCandidate> only(Collection<PaymentCandidate> payments,
                                               Predicate<PaymentCandidate> test) {
        return payments.stream().filter(Objects::nonNull).filter(test).toList();
    }
}
