package com.xavier.backend.customers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.model.Allocation.Part;
import com.xavier.backend.customers.model.Allocation.Slot;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationTest {

    final UUID first = UUID.randomUUID();
    final UUID second = UUID.randomUUID();
    final UUID third = UUID.randomUUID();

    @Test
    void fillsEachEntryInOrderAndSplitsTheLastOne() {
        List<Part> parts = Allocation.fillInOrder(new BigDecimal("70.00"), List.of(
                new Slot(first, new BigDecimal("50.00")), new Slot(second, new BigDecimal("30.00")),
                new Slot(third, new BigDecimal("10.00"))));

        assertThat(parts).containsExactly(new Part(first, new BigDecimal("50.00")),
                new Part(second, new BigDecimal("20.00")));
    }

    @Test
    void skipsEntriesWithNothingLeft() {
        assertThat(Allocation.fillInOrder(new BigDecimal("5.00"), List.of(new Slot(first, BigDecimal.ZERO),
                new Slot(second, new BigDecimal("8.00")))))
                .containsExactly(new Part(second, new BigDecimal("5.00")));
    }

    @Test
    void refusesMoreThanTheEntriesCanTake() {
        assertThatThrownBy(() -> Allocation.fillInOrder(new BigDecimal("10.01"),
                List.of(new Slot(first, new BigDecimal("10.00")))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocation.fillInOrder(BigDecimal.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
