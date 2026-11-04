package com.xavier.backend.cash.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** spec §8.3 CashClosing: DRAFT → SUBMITTED → APPROVED | REJECTED; a resubmission is a new row. */
class CashClosingTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-04T22:00:00Z");
    static final UUID ACTOR = UUID.randomUUID();

    @Test
    void aLinePerMethodComparesTheCountWithTheExpectedAmount() {
        CashClosing closing = draft(Map.of("CASH", money("100.00"), "CARD", money("50.00")),
                Map.of("CASH", money("95.00")));

        assertThat(closing.getLines()).hasSize(2);
        assertThat(line(closing, "CASH").getDifference()).isEqualTo(money("-5.00"));
        assertThat(line(closing, "CARD").getCounted()).isEqualTo(money("0.00"));
        assertThat(line(closing, "CARD").getDifference()).isEqualTo(money("-50.00"));
        assertThat(closing.totalAbsoluteDifference()).isEqualByComparingTo(new BigDecimal("55.00"));
    }

    @Test
    void aDifferenceNeedsAJustificationBeforeSubmission() {
        CashClosing closing = draft(Map.of("CASH", money("100.00")), Map.of("CASH", money("95.00")));

        assertThatThrownBy(() -> closing.submit(ACTOR, NOW)).isInstanceOfSatisfying(
                CashRuleViolationException.class, e -> assertThat(e.rule()).isEqualTo(CashRule.JUSTIFICATION_REQUIRED));

        closing.justify(Map.of("CASH", "coins short"), NOW);
        closing.submit(ACTOR, NOW);
        assertThat(closing.getStatus()).isEqualTo(CashClosingStatus.SUBMITTED);
    }

    @Test
    void aClosingWithoutDifferenceSubmitsWithoutJustification() {
        CashClosing closing = draft(Map.of("CASH", money("100.00")), Map.of("CASH", money("100.00")));

        closing.submit(ACTOR, NOW);

        assertThat(closing.getStatus()).isEqualTo(CashClosingStatus.SUBMITTED);
    }

    @Test
    void justifyingAnUnknownMethodIsRefused() {
        CashClosing closing = draft(Map.of("CASH", money("1.00")), Map.of());

        assertThatThrownBy(() -> closing.justify(Map.of("PIX", "x"), NOW)).isInstanceOfSatisfying(
                CashRuleViolationException.class, e -> assertThat(e.rule()).isEqualTo(CashRule.UNKNOWN_METHOD));
    }

    @Test
    void aDraftCannotBeApprovedOrRejected() {
        CashClosing closing = balancedDraft();

        assertThatThrownBy(() -> closing.approve(ACTOR, ActorKind.ACCOUNT, NOW))
                .isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> closing.reject(ACTOR, ActorKind.ACCOUNT, "no", NOW))
                .isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void aSubmittedClosingIsApprovedOrRejectedOnceAndThenFrozen() {
        CashClosing approved = submitted();
        approved.approve(ACTOR, ActorKind.ACCOUNT, NOW);
        assertThat(approved.getStatus()).isEqualTo(CashClosingStatus.APPROVED);

        CashClosing rejected = submitted();
        rejected.reject(ACTOR, ActorKind.ACCOUNT, "recount", NOW);
        assertThat(rejected.getStatus()).isEqualTo(CashClosingStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("recount");

        for (CashClosing decided : new CashClosing[] {approved, rejected}) {
            assertThatThrownBy(() -> decided.approve(ACTOR, ActorKind.ACCOUNT, NOW))
                    .isInstanceOf(IllegalCashStateException.class);
            assertThatThrownBy(() -> decided.reject(ACTOR, ActorKind.ACCOUNT, "x", NOW))
                    .isInstanceOf(IllegalCashStateException.class);
            assertThatThrownBy(() -> decided.submit(ACTOR, NOW)).isInstanceOf(IllegalCashStateException.class);
            assertThatThrownBy(() -> decided.justify(Map.of(), NOW)).isInstanceOf(IllegalCashStateException.class);
        }
    }

    @Test
    void aSubmittedClosingCannotBeEditedOrSubmittedAgain() {
        CashClosing closing = submitted();

        assertThatThrownBy(() -> closing.justify(Map.of(), NOW)).isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> closing.submit(ACTOR, NOW)).isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void aRejectionNeedsAReason() {
        assertThatThrownBy(() -> submitted().reject(ACTOR, ActorKind.ACCOUNT, "", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static CashClosing draft(Map<String, Money> expected, Map<String, Money> counted) {
        return CashClosing.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, null, expected, counted,
                MZN, ACTOR, NOW);
    }

    static CashClosing balancedDraft() {
        return draft(Map.of("CASH", money("10.00")), Map.of("CASH", money("10.00")));
    }

    static CashClosing submitted() {
        CashClosing closing = balancedDraft();
        closing.submit(ACTOR, NOW);
        return closing;
    }

    static CashClosingLine line(CashClosing closing, String method) {
        return closing.getLines().stream().filter(l -> l.getMethodCode().equals(method)).findFirst().orElseThrow();
    }

    static Money money(String amount) {
        return Money.of(amount, MZN);
    }
}
