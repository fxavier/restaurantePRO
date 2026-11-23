package com.xavier.backend.orders.model.dto;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The order side of the POS table map (light reference, 2026-11-23): for each seated OPEN order of the session's
 * store, the table it sits at now, its {@code total} (the order's {@code due}: Σ gross of the confirmed lines for
 * their net quantities, D59, the panel's total) and {@code lastRoundAt}, when its latest round was confirmed
 * ({@code null} before the first). {@code overdueAfterMinutes} is how long a table may go without a round before the
 * map shows it as overdue. Dining owns the map but cannot read orders (orders depends on dining, D35), so the POS
 * merges the two by order id.
 */
@NullMarked
public record TableActivityView(int overdueAfterMinutes, List<Table> tables) {

    public record Table(UUID tableId, UUID orderId, Money total, @Nullable Instant lastRoundAt) {
    }
}
