package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A bill partition as the POS sees it (D67). {@code kind} is {@code ITEMS} or {@code AMOUNT}; {@code status}
 * {@code LIVE} or {@code CANCELLED}; {@code groupId}, {@code partNo} and {@code parts} are set for equal parts. Derived:
 * {@code paid} (Σ its payments), {@code credit} (put on account, S29), {@code balance = due − paid − credit},
 * {@code financialState}, and {@code invoiced} (its own document is ISSUED, with {@code documentId} once drafted).
 */
public record PartitionView(UUID id, UUID orderId, String kind, String status, String label, UUID groupId,
                            Integer partNo, Integer parts, List<Line> lines, Money due, Money paid, Money credit,
                            Money balance, String financialState, boolean invoiced, UUID documentId, long version,
                            Instant createdAt) {

    /** One order line's share: quantity and its D68 amounts. */
    public record Line(UUID lineId, String description, BigDecimal quantity, Money gross, Money net, Money tax) {
    }
}
