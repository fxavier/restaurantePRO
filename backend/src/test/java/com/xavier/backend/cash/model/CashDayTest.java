package com.xavier.backend.cash.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/** spec §8.3 CashDay: OPEN → OPERATIONALLY_CLOSED → FINANCIALLY_CLOSED; reopen is an event back to OPEN (P19). */
class CashDayTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final UUID ACTOR = UUID.randomUUID();

    @Test
    void anOpenDayClosesOperationallyThenFinancially() {
        CashDay day = open();

        day.closeOperationally(ACTOR, ActorKind.EMPLOYEE, NOW);
        assertThat(day.getStatus()).isEqualTo(CashDayStatus.OPERATIONALLY_CLOSED);

        day.closeFinancially(ACTOR, ActorKind.ACCOUNT, NOW);
        assertThat(day.getStatus()).isEqualTo(CashDayStatus.FINANCIALLY_CLOSED);
    }

    @Test
    void onlyAnOpenDayAcceptsSessions() {
        open().requireOpen();

        CashDay closed = operationallyClosed();
        assertThatThrownBy(closed::requireOpen).isInstanceOfSatisfying(CashRuleViolationException.class,
                e -> assertThat(e.rule()).isEqualTo(CashRule.DAY_NOT_OPEN));
    }

    @Test
    void anOpenDayCannotCloseFinanciallyOrReopen() {
        assertThatThrownBy(() -> open().closeFinancially(ACTOR, ActorKind.ACCOUNT, NOW))
                .isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> open().reopen(ACTOR, ActorKind.ACCOUNT, UUID.randomUUID(), "x", NOW))
                .isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void anOperationallyClosedDayCannotCloseOperationallyAgain() {
        assertThatThrownBy(() -> operationallyClosed().closeOperationally(ACTOR, ActorKind.EMPLOYEE, NOW))
                .isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void aFinanciallyClosedDayAcceptsNoCommand() {
        CashDay day = operationallyClosed();
        day.closeFinancially(ACTOR, ActorKind.ACCOUNT, NOW);

        assertThatThrownBy(() -> day.closeOperationally(ACTOR, ActorKind.EMPLOYEE, NOW))
                .isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> day.closeFinancially(ACTOR, ActorKind.ACCOUNT, NOW))
                .isInstanceOf(IllegalCashStateException.class);
        assertThatThrownBy(() -> day.reopen(ACTOR, ActorKind.ACCOUNT, UUID.randomUUID(), "x", NOW))
                .isInstanceOf(IllegalCashStateException.class);
    }

    @Test
    void reopeningReturnsTheDayToOpenAndKeepsThePreviousCloseInTheReopeningFact() {
        CashDay day = operationallyClosed();
        UUID approver = UUID.randomUUID();

        CashDayReopening reopening = day.reopen(ACTOR, ActorKind.ACCOUNT, approver, "forgotten receipt", NOW);

        assertThat(day.getStatus()).isEqualTo(CashDayStatus.OPEN);
        assertThat(reopening.getApproverEmployeeId()).isEqualTo(approver);
        assertThat(reopening.getPreviousClosedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(reopening.getReason()).isEqualTo("forgotten receipt");
    }

    @Test
    void aReopenNeedsAReason() {
        assertThatThrownBy(() -> operationallyClosed().reopen(ACTOR, ActorKind.ACCOUNT, UUID.randomUUID(), " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A day as Hibernate loads it from the row {@code CashDayRepository.createIfAbsent} inserted. */
    static CashDay open() {
        CashDay day = BeanUtils.instantiateClass(CashDay.class);
        ReflectionTestUtils.setField(day, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(day, "storeId", UUID.randomUUID());
        ReflectionTestUtils.setField(day, "businessDate", LocalDate.of(2026, 11, 4));
        ReflectionTestUtils.setField(day, "status", CashDayStatus.OPEN);
        ReflectionTestUtils.setField(day, "openedAt", NOW);
        return day;
    }

    static CashDay operationallyClosed() {
        CashDay day = open();
        day.closeOperationally(ACTOR, ActorKind.EMPLOYEE, NOW.minusSeconds(60));
        return day;
    }
}
