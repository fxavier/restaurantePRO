package com.xavier.backend.customers.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Spreads an amount over entries in the caller's order (D73: FIFO, named debits, latest-first reopening). */
public final class Allocation {

    private Allocation() {
    }

    /** An entry and how much of the amount it can still take. */
    public record Slot(UUID entryId, BigDecimal capacity) {
    }

    public record Part(UUID entryId, BigDecimal amount) {
    }

    /**
     * Gives each slot, in order, as much as it can take until {@code amount} is used up.
     *
     * @throws IllegalArgumentException when {@code amount} is not positive or the slots cannot take all of it
     */
    public static List<Part> fillInOrder(BigDecimal amount, List<Slot> slots) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("An allocated amount must be positive");
        }
        List<Part> parts = new ArrayList<>();
        BigDecimal left = amount;
        for (Slot slot : slots) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal taken = left.min(slot.capacity());
            if (taken.signum() > 0) {
                parts.add(new Part(slot.entryId(), taken));
                left = left.subtract(taken);
            }
        }
        if (left.signum() > 0) {
            throw new IllegalArgumentException("The entries can take only " + amount.subtract(left) + " of " + amount);
        }
        return parts;
    }
}
