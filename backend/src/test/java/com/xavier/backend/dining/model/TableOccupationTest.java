package com.xavier.backend.dining.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.dining.exception.IllegalOccupationStateException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.model.dto.CloseReason;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The §8.3 machine OPEN → CLOSED over every command (r2-decisions §4 dining). */
class TableOccupationTest {

    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final Instant LATER = NOW.plus(Duration.ofMinutes(40));
    static final UUID STORE = UUID.randomUUID();
    static final UUID TABLE = UUID.randomUUID();
    static final UUID OTHER_TABLE = UUID.randomUUID();
    static final UUID ORDER = UUID.randomUUID();
    static final UUID EMPLOYEE = UUID.randomUUID();

    @Test
    void anOpenedOccupationIsOpenWithItsGuestsAndOpeningEmployee() {
        TableOccupation occupation = open();

        assertThat(occupation.isOpen()).isTrue();
        assertThat(occupation.toView())
                .extracting("tableId", "orderId", "guests", "openedAt", "openedByEmployeeId", "subtotalPrintedAt")
                .containsExactly(TABLE, ORDER, 3, NOW, EMPLOYEE, null);
    }

    @Test
    void anOccupationNeedsAtLeastOneGuest() {
        assertThatThrownBy(() -> TableOccupation.open(STORE, TABLE, ORDER, 0, EMPLOYEE, NOW))
                .isInstanceOf(InvalidDiningSetupException.class);
    }

    @Test
    void closingAnOpenOccupationRecordsWhenAndWhy() {
        TableOccupation occupation = open();

        occupation.close(CloseReason.ORDER_CLOSED, LATER);

        assertThat(occupation.isOpen()).isFalse();
        assertThat(occupation.getClosedAt()).isEqualTo(LATER);
        assertThat(occupation.getCloseReason()).isEqualTo(CloseReason.ORDER_CLOSED);
    }

    @Test
    void aClosedOccupationCannotBeClosedAgain() {
        TableOccupation occupation = open();
        occupation.close(CloseReason.ORDER_CANCELLED, LATER);

        assertThatThrownBy(() -> occupation.close(CloseReason.ORDER_CLOSED, LATER))
                .isInstanceOf(IllegalOccupationStateException.class)
                .satisfies(e -> assertThat(((IllegalOccupationStateException) e).code()).isEqualTo("OCCUPATION_CLOSED"));
        assertThat(occupation.getCloseReason()).isEqualTo(CloseReason.ORDER_CANCELLED);
    }

    @Test
    void printingTheSubtotalMarksAnOpenOccupation() {
        TableOccupation occupation = open();

        occupation.markSubtotalPrinted(LATER);

        assertThat(occupation.toView().subtotalPrintedAt()).isEqualTo(LATER);
    }

    @Test
    void aClosedOccupationCannotBeMarkedSubtotalPrinted() {
        TableOccupation occupation = open();
        occupation.close(CloseReason.ORDER_CLOSED, LATER);

        assertThatThrownBy(() -> occupation.markSubtotalPrinted(LATER))
                .isInstanceOf(IllegalOccupationStateException.class);
    }

    @Test
    void movingClosesThisOccupationAsMovedAndContinuesThePartyOnTheTargetTable() {
        TableOccupation occupation = open();
        occupation.markSubtotalPrinted(NOW);

        TableOccupation moved = occupation.moveTo(OTHER_TABLE, LATER);

        assertThat(occupation.isOpen()).isFalse();
        assertThat(occupation.getCloseReason()).isEqualTo(CloseReason.MOVED);
        assertThat(occupation.getClosedAt()).isEqualTo(LATER);
        assertThat(moved.isOpen()).isTrue();
        assertThat(moved.toView())
                .extracting("tableId", "orderId", "guests", "openedAt", "openedByEmployeeId", "subtotalPrintedAt")
                .containsExactly(OTHER_TABLE, ORDER, 3, NOW, EMPLOYEE, NOW);
    }

    @Test
    void aClosedOccupationCannotBeMoved() {
        TableOccupation occupation = open();
        occupation.close(CloseReason.ORDER_CLOSED, LATER);

        assertThatThrownBy(() -> occupation.moveTo(OTHER_TABLE, LATER))
                .isInstanceOf(IllegalOccupationStateException.class);
    }

    private static TableOccupation open() {
        return TableOccupation.open(STORE, TABLE, ORDER, 3, EMPLOYEE, NOW);
    }
}
