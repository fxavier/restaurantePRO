package com.xavier.backend.settlement.reconciliation.model;

import static com.xavier.backend.settlement.reconciliation.model.EntryKind.AMOUNT_MISMATCH;
import static com.xavier.backend.settlement.reconciliation.model.EntryKind.DUPLICATE_LINE;
import static com.xavier.backend.settlement.reconciliation.model.EntryKind.MATCHED;
import static com.xavier.backend.settlement.reconciliation.model.EntryKind.MISSING_INTERNAL;
import static com.xavier.backend.settlement.reconciliation.model.EntryKind.MISSING_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.xavier.backend.settlement.model.PaymentMethodKind;
import com.xavier.backend.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * D78 matching: by the provider's reference (INTEGRATED), by authorization code and amount (EXTERNAL_TERMINAL),
 * otherwise by amount within ±10 minutes, and only ever on exactly one candidate. Ambiguity is never resolved by
 * guessing: the line stays MISSING_INTERNAL and the payments MISSING_PROVIDER, for a person to resolve.
 */
class StatementMatcherTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant FROM = Instant.parse("2026-11-04T22:00:00Z");
    static final Instant TO = Instant.parse("2026-11-05T22:00:00Z");
    static final Instant NOON = Instant.parse("2026-11-05T10:00:00Z");

    static final UUID P1 = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    static final UUID P2 = UUID.fromString("0190a000-0000-7000-8000-000000000002");

    static StatementRow row(int lineNo, String amount, Instant at, String reference, String authorization) {
        return new StatementRow(lineNo, at, Money.of(amount, MZN), Money.of("0", MZN), reference, authorization,
                null);
    }

    static PaymentCandidate payment(UUID id, String net, Instant at) {
        return new PaymentCandidate(id, Money.of(net, MZN), at, null, null, null, false);
    }

    static PaymentCandidate payment(UUID id, String net, Instant at, String providerReference,
                                    String providerTransactionRef, String authorization) {
        return new PaymentCandidate(id, Money.of(net, MZN), at, providerReference, providerTransactionRef,
                authorization, false);
    }

    static PaymentCandidate matchedEarlier(PaymentCandidate p) {
        return new PaymentCandidate(p.paymentId(), p.net(), p.confirmedAt(), p.providerReference(),
                p.providerTransactionRef(), p.authorizationCode(), true);
    }

    static List<Match> match(PaymentMethodKind kind, List<StatementRow> rows, List<PaymentCandidate> payments) {
        return StatementMatcher.match(kind, rows, payments, FROM, TO);
    }

    /** {@code (lineNo or null, kind, paymentId or null)} per outcome, in the matcher's order. */
    static List<org.assertj.core.groups.Tuple> outcomes(List<Match> matches) {
        return matches.stream().map(m -> tuple(m.row() == null ? null : m.row().lineNo(), m.kind(),
                m.payment() == null ? null : m.payment().paymentId())).toList();
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                Arguments.of("integrated: our reference", PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, "ATT-1", null)),
                        List.of(payment(P1, "25.00", NOON.minus(Duration.ofHours(3)), "ATT-1", "TX-1", null)),
                        List.of(tuple(1, MATCHED, P1))),
                Arguments.of("integrated: the provider's transaction reference", PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, "TX-1", null)),
                        List.of(payment(P1, "25.00", NOON, "ATT-1", "TX-1", null)),
                        List.of(tuple(1, MATCHED, P1))),
                Arguments.of("integrated: the reference with another amount", PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "24.00", NOON, "ATT-1", null)),
                        List.of(payment(P1, "25.00", NOON, "ATT-1", null, null)),
                        List.of(tuple(1, AMOUNT_MISMATCH, P1))),
                Arguments.of("integrated: an unknown reference never falls back to the amount",
                        PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, "ATT-X", null)),
                        List.of(payment(P1, "25.00", NOON, "ATT-1", null, null)),
                        List.of(tuple(1, MISSING_INTERNAL, null), tuple(null, MISSING_PROVIDER, P1))),
                Arguments.of("external: authorization code and amount", PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "25.00", NOON.plus(Duration.ofHours(2)), null, "A1")),
                        List.of(payment(P1, "25.00", NOON, null, null, "A1"),
                                payment(P2, "25.00", NOON, null, null, "B2")),
                        List.of(tuple(1, MATCHED, P1), tuple(null, MISSING_PROVIDER, P2))),
                Arguments.of("external: the only payment with the code, another amount",
                        PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "20.00", NOON, null, "A1")),
                        List.of(payment(P1, "25.00", NOON, null, null, "A1")),
                        List.of(tuple(1, AMOUNT_MISMATCH, P1))),
                Arguments.of("external: two payments with the code and the amount", PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "25.00", NOON, null, "A1")),
                        List.of(payment(P1, "25.00", NOON, null, null, "A1"),
                                payment(P2, "25.00", NOON.plusSeconds(30), null, null, "A1")),
                        List.of(tuple(1, MISSING_INTERNAL, null), tuple(null, MISSING_PROVIDER, P1),
                                tuple(null, MISSING_PROVIDER, P2))),
                Arguments.of("no key: one candidate within ten minutes", PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "25.00", NOON.plus(Duration.ofMinutes(10)), null, null)),
                        List.of(payment(P1, "25.00", NOON)),
                        List.of(tuple(1, MATCHED, P1))),
                Arguments.of("no key: eleven minutes is outside the window", PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "25.00", NOON.plus(Duration.ofMinutes(11)), null, null)),
                        List.of(payment(P1, "25.00", NOON)),
                        List.of(tuple(1, MISSING_INTERNAL, null), tuple(null, MISSING_PROVIDER, P1))),
                Arguments.of("no key: two equal candidates are never paired at random",
                        PaymentMethodKind.EXTERNAL_TERMINAL,
                        List.of(row(1, "25.00", NOON, null, null)),
                        List.of(payment(P1, "25.00", NOON), payment(P2, "25.00", NOON.plusSeconds(40))),
                        List.of(tuple(1, MISSING_INTERNAL, null), tuple(null, MISSING_PROVIDER, P1),
                                tuple(null, MISSING_PROVIDER, P2))),
                Arguments.of("no key: two lines for one candidate match neither", PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, null, null), row(2, "25.00", NOON.plusSeconds(60), null, null)),
                        List.of(payment(P1, "25.00", NOON)),
                        List.of(tuple(1, MISSING_INTERNAL, null), tuple(2, MISSING_INTERNAL, null),
                                tuple(null, MISSING_PROVIDER, P1))),
                Arguments.of("no key: a payment the reference already matched is not a candidate",
                        PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, null, null), row(2, "25.00", NOON, "ATT-1", null)),
                        List.of(payment(P1, "25.00", NOON, "ATT-1", null, null), payment(P2, "25.00", NOON)),
                        List.of(tuple(1, MATCHED, P2), tuple(2, MATCHED, P1))),
                Arguments.of("the same reference twice: the second line is a duplicate", PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, "ATT-1", null), row(2, "25.00", NOON, "ATT-1", null)),
                        List.of(payment(P1, "25.00", NOON, "ATT-1", null, null)),
                        List.of(tuple(1, MATCHED, P1), tuple(2, DUPLICATE_LINE, P1))),
                Arguments.of("matched by an earlier batch: a duplicate line, and not missing",
                        PaymentMethodKind.INTEGRATED,
                        List.of(row(1, "25.00", NOON, "ATT-1", null), row(2, "10.00", NOON, null, null)),
                        List.of(matchedEarlier(payment(P1, "25.00", NOON, "ATT-1", null, null)),
                                matchedEarlier(payment(P2, "10.00", NOON))),
                        List.of(tuple(1, DUPLICATE_LINE, P1), tuple(2, DUPLICATE_LINE, P2))),
                Arguments.of("missing at the provider: only payments of the period with money left",
                        PaymentMethodKind.INTEGRATED,
                        List.of(),
                        List.of(payment(P1, "25.00", TO.minusSeconds(1)), payment(P2, "0.00", NOON),
                                payment(UUID.randomUUID(), "5.00", TO),
                                payment(UUID.randomUUID(), "5.00", FROM.minusSeconds(1))),
                        List.of(tuple(null, MISSING_PROVIDER, P1))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void matchesOnlyOnExactlyOneCandidate(String name, PaymentMethodKind kind, List<StatementRow> rows,
                                          List<PaymentCandidate> payments, List<org.assertj.core.groups.Tuple> expected) {
        assertThat(outcomes(match(kind, rows, payments))).containsExactlyElementsOf(expected);
    }

    @Test
    void aMatchCarriesBothAmountsAndTheFee() {
        StatementRow line = new StatementRow(1, NOON, Money.of("24.00", MZN), Money.of("0.40", MZN), "ATT-1", null,
                "L1");

        Match match = match(PaymentMethodKind.INTEGRATED, List.of(line),
                List.of(payment(P1, "25.00", NOON, "ATT-1", null, null))).getFirst();

        assertThat(match.internalAmount()).isEqualTo(Money.of("25.00", MZN));
        assertThat(match.providerAmount()).isEqualTo(Money.of("24.00", MZN));
        assertThat(match.difference()).isEqualTo(Money.of("-1.00", MZN));
    }

    /**
     * Properties over random statements of one method (seeded, so a failure repeats): every line has one outcome;
     * no payment is matched twice; an amount-and-time match always had exactly one candidate left; and the
     * outcomes do not depend on the order of the lines.
     */
    @Test
    void randomStatementsKeepTheMatchingRules() {
        for (int seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            List<PaymentCandidate> payments = new ArrayList<>();
            List<StatementRow> rows = new ArrayList<>();
            String[] amounts = {"10.00", "25.00", "25.00", "40.00"};
            for (int i = 0; i < 1 + random.nextInt(8); i++) {
                Instant at = NOON.plus(Duration.ofMinutes(random.nextInt(40)));
                String amount = amounts[random.nextInt(amounts.length)];
                String auth = random.nextBoolean() ? "A" + random.nextInt(3) : null;
                PaymentCandidate p = payment(new UUID(seed, i), amount, at, null, null, auth);
                payments.add(random.nextInt(6) == 0 ? matchedEarlier(p) : p);
                if (random.nextInt(4) != 0) {
                    rows.add(row(0, random.nextInt(5) == 0 ? "11.00" : amount,
                            at.plus(Duration.ofMinutes(random.nextInt(13) - 6)), null,
                            random.nextBoolean() ? auth : null));
                }
            }
            List<StatementRow> numbered = IntStream.range(0, rows.size()).mapToObj(i -> renumber(rows.get(i), i + 1))
                    .toList();

            List<Match> result = match(PaymentMethodKind.EXTERNAL_TERMINAL, numbered, payments);

            assertThat(result.stream().filter(m -> m.row() != null)).as("seed %d", seed).hasSize(numbered.size());
            Map<UUID, Integer> paired = new HashMap<>();
            result.stream().filter(m -> m.kind() == MATCHED || m.kind() == AMOUNT_MISMATCH)
                    .forEach(m -> paired.merge(m.payment().paymentId(), 1, Integer::sum));
            assertThat(paired.values()).as("seed %d", seed).allMatch(count -> count == 1);
            for (Match m : result) {
                if (m.kind() == MATCHED && m.row().authorizationCode() == null) {
                    assertThat(payments.stream().filter(p -> !paired.containsKey(p.paymentId())
                                    || p.paymentId().equals(m.payment().paymentId()))
                            .filter(p -> p.net().equals(m.row().amount()))
                            .filter(p -> !Duration.between(p.confirmedAt(), m.row().occurredAt()).abs()
                                    .minus(StatementMatcher.WINDOW).isPositive()))
                            .as("seed %d: an amount match had one candidate", seed).hasSize(1);
                }
            }
            List<StatementRow> shuffled = new ArrayList<>(numbered);
            Collections.shuffle(shuffled, random);
            assertThat(byLine(match(PaymentMethodKind.EXTERNAL_TERMINAL, shuffled, payments)))
                    .as("seed %d: line order does not matter", seed).isEqualTo(byLine(result));
        }
    }

    static StatementRow renumber(StatementRow row, int lineNo) {
        return new StatementRow(lineNo, row.occurredAt(), row.amount(), row.fee(), row.reference(),
                row.authorizationCode(), row.lineRef());
    }

    /** Outcomes keyed by line (missing-provider ones by payment), ignoring which of two duplicates came first. */
    static Map<Object, EntryKind> byLine(List<Match> matches) {
        Map<Object, EntryKind> keyed = new HashMap<>();
        for (Match m : matches) {
            keyed.put(m.row() == null ? m.payment().paymentId() : m.row().lineNo(), m.kind());
        }
        return keyed;
    }
}
