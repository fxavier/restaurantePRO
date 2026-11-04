package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The PaymentAttempt machine (spec §8.3, r2-decisions §4 settlement): every state × every command. */
class PaymentAttemptTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    static final UUID EMPLOYEE = UUID.randomUUID();

    @Test
    void cashSucceedsAtOnceWithItsChange() {
        PaymentAttempt attempt = cash("25.00", "30.00");

        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(attempt.change()).isEqualTo(mzn("5.00"));
    }

    @Test
    void cashNeedsTenderedOfAtLeastTheAmount() {
        assertThatThrownBy(() -> cash("25.00", "20.00"))
                .isInstanceOf(SettlementRuleException.class)
                .hasFieldOrPropertyWithValue("code", "TENDERED_BELOW_AMOUNT");
        assertThatThrownBy(() -> cash("25.00", null))
                .hasFieldOrPropertyWithValue("code", "TENDERED_BELOW_AMOUNT");
    }

    @Test
    void changeIsForCashOnly() {
        assertThatThrownBy(() -> PaymentAttempt.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), method(PaymentMethodKind.EXTERNAL_TERMINAL), mzn("10.00"), mzn("20.00"),
                EMPLOYEE, NOW))
                .hasFieldOrPropertyWithValue("code", "CHANGE_FOR_CASH_ONLY");
    }

    @Test
    void anAmountMustBePositive() {
        assertThatThrownBy(() -> cash("0.00", "0.00")).hasFieldOrPropertyWithValue("code", "INVALID_AMOUNT");
    }

    @Test
    void externalTerminalWaitsForTheOperatorAndIntegratedForTheProvider() {
        assertThat(attempt(PaymentMethodKind.EXTERNAL_TERMINAL).getStatus()).isEqualTo(AttemptStatus.PENDING);
        PaymentAttempt integrated = attempt(PaymentMethodKind.INTEGRATED);
        assertThat(integrated.getStatus()).isEqualTo(AttemptStatus.CREATED);
        assertThat(integrated.getProviderReference()).isEqualTo(integrated.getId().toString());
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void onlyAPendingExternalTerminalAttemptIsConfirmed(AttemptStatus from) {
        expect(PaymentMethodKind.EXTERNAL_TERMINAL, from, a -> a.confirm("AUTH-1", NOW),
                EnumSet.of(AttemptStatus.PENDING), AttemptStatus.SUCCEEDED);
    }

    @Test
    void anIntegratedAttemptIsNeverConfirmedByTheOperator() {
        PaymentAttempt attempt = in(PaymentMethodKind.INTEGRATED, AttemptStatus.PENDING);
        assertThatThrownBy(() -> attempt.confirm(null, NOW)).isInstanceOf(SettlementConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void anExternalTerminalAttemptIsCancelledWhileCreatedOrPending(AttemptStatus from) {
        expect(PaymentMethodKind.EXTERNAL_TERMINAL, from, a -> a.cancel("customer left", EMPLOYEE, NOW),
                EnumSet.of(AttemptStatus.CREATED, AttemptStatus.PENDING), AttemptStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void anIntegratedAttemptIsCancelledOnlyBeforeTheProviderIsCalled(AttemptStatus from) {
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.cancel("customer left", EMPLOYEE, NOW),
                EnumSet.of(AttemptStatus.CREATED), AttemptStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void theSubmitterMovesOnlyACreatedAttemptToPending(AttemptStatus from) {
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.submit(NOW), EnumSet.of(AttemptStatus.CREATED),
                AttemptStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void theProviderOutcomeIsRecordedOnlyOnAPendingAttempt(AttemptStatus from) {
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.recordProviderSuccess("TX-9", NOW),
                EnumSet.of(AttemptStatus.PENDING), AttemptStatus.SUCCEEDED);
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.recordProviderFailure("declined", NOW),
                EnumSet.of(AttemptStatus.PENDING), AttemptStatus.FAILED);
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.markUnknown("timeout", NOW),
                EnumSet.of(AttemptStatus.PENDING), AttemptStatus.UNKNOWN);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void aProviderQueryResolvesAnUnknownOrPendingAttempt(AttemptStatus from) {
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.resolveFromProvider(true, "TX-9", null, EMPLOYEE, NOW),
                EnumSet.of(AttemptStatus.UNKNOWN, AttemptStatus.PENDING), AttemptStatus.SUCCEEDED);
        expect(PaymentMethodKind.INTEGRATED, from, a -> a.resolveFromProvider(false, null, "declined", EMPLOYEE, NOW),
                EnumSet.of(AttemptStatus.UNKNOWN, AttemptStatus.PENDING), AttemptStatus.FAILED);
    }

    @ParameterizedTest
    @EnumSource(AttemptStatus.class)
    void onlyAnUnknownAttemptIsReclassifiedByHand(AttemptStatus from) {
        UUID approver = UUID.randomUUID();
        expect(PaymentMethodKind.INTEGRATED, from,
                a -> a.reclassify(AttemptStatus.SUCCEEDED, "bank statement", EMPLOYEE, approver, NOW),
                EnumSet.of(AttemptStatus.UNKNOWN), AttemptStatus.SUCCEEDED);
    }

    @Test
    void aReclassificationNeedsAReasonAndAFinalOutcome() {
        PaymentAttempt attempt = in(PaymentMethodKind.INTEGRATED, AttemptStatus.UNKNOWN);
        UUID approver = UUID.randomUUID();
        assertThatThrownBy(() -> attempt.reclassify(AttemptStatus.FAILED, " ", EMPLOYEE, approver, NOW))
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
        assertThatThrownBy(() -> attempt.reclassify(AttemptStatus.CANCELLED, "x", EMPLOYEE, approver, NOW))
                .hasFieldOrPropertyWithValue("code", "INVALID_OUTCOME");
    }

    @Test
    void anOpenAttemptIsInDoubtAndAFinalOneIsNot() {
        assertThat(AttemptStatus.OPEN).containsExactlyInAnyOrder(AttemptStatus.CREATED, AttemptStatus.PENDING,
                AttemptStatus.UNKNOWN);
    }

    private static void expect(PaymentMethodKind kind, AttemptStatus from, Consumer<PaymentAttempt> command,
                               Set<AttemptStatus> allowed, AttemptStatus to) {
        PaymentAttempt attempt = in(kind, from);
        assumeTrue(attempt != null, () -> kind + " is never " + from);
        if (allowed.contains(from)) {
            command.accept(attempt);
            assertThat(attempt.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> command.accept(attempt)).isInstanceOf(SettlementConflictException.class)
                    .hasFieldOrPropertyWithValue("code", "ILLEGAL_ATTEMPT_STATE");
            assertThat(attempt.getStatus()).isEqualTo(from);
        }
    }

    /**
     * An attempt of {@code kind} brought to {@code status} through its own transitions, or {@code null} when that
     * kind never reaches it (cash is born SUCCEEDED; an external terminal attempt is born PENDING and never FAILED
     * or UNKNOWN: the operator confirms or cancels it).
     */
    static PaymentAttempt in(PaymentMethodKind kind, AttemptStatus status) {
        PaymentAttempt attempt = attempt(kind);
        if (kind == PaymentMethodKind.CASH) {
            return status == AttemptStatus.SUCCEEDED ? attempt : null;
        }
        boolean integrated = kind == PaymentMethodKind.INTEGRATED;
        switch (status) {
            case CREATED -> {
                return integrated ? attempt : null;
            }
            case PENDING -> {
                if (integrated) {
                    attempt.submit(NOW);
                }
            }
            case SUCCEEDED -> {
                if (integrated) {
                    attempt.submit(NOW);
                    attempt.recordProviderSuccess("TX", NOW);
                } else {
                    attempt.confirm(null, NOW);
                }
            }
            case FAILED, UNKNOWN -> {
                if (!integrated) {
                    return null;
                }
                attempt.submit(NOW);
                if (status == AttemptStatus.FAILED) {
                    attempt.recordProviderFailure("declined", NOW);
                } else {
                    attempt.markUnknown("timeout", NOW);
                }
            }
            case CANCELLED -> attempt.cancel("x", EMPLOYEE, NOW);
        }
        return attempt;
    }

    static PaymentAttempt attempt(PaymentMethodKind kind) {
        return PaymentAttempt.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                method(kind), mzn("10.00"), null, EMPLOYEE, NOW);
    }

    static PaymentAttempt cash(String amount, String tendered) {
        return PaymentAttempt.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                method(PaymentMethodKind.CASH), mzn(amount), tendered == null ? null : mzn(tendered), EMPLOYEE, NOW);
    }

    static PaymentMethod method(PaymentMethodKind kind) {
        return switch (kind) {
            case CASH -> PaymentMethod.create("CASH", "Numerário", PaymentMethodKind.CASH, null, NOW);
            case EXTERNAL_TERMINAL -> PaymentMethod.create("CARD", "Cartão", kind, null, NOW);
            case INTEGRATED -> PaymentMethod.create("MPESA", "M-Pesa", kind, "FAKE", NOW);
        };
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
