package com.xavier.backend.orders.model.event;

import com.xavier.backend.orders.OrderMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * Part or all of a confirmed line was voided (published by S14): production prints a VOID ticket for {@code quantity}
 * of {@code line} on its destination and, for a menu, on each of its components' destinations (D108).
 *
 * @param line R4: a menu line's {@code components} carry the quantity voided of each component (per menu unit ×
 *             {@code quantity}); {@code null} in a publication stored before R4
 * @param mode R4: how the order is served; {@code null} in a publication stored before R4. It stays last.
 */
@NamedInterface("event")
public record OrderLinesVoided(UUID orderId, UUID voidId, UUID storeId, UUID terminalId, long orderNumber,
                               String tableCode, Line line, BigDecimal quantity, String reason, Instant at,
                               OrderMode mode) {

    /** The R2/R3 shape, without the mode. */
    public OrderLinesVoided(UUID orderId, UUID voidId, UUID storeId, UUID terminalId, long orderNumber,
                            String tableCode, Line line, BigDecimal quantity, String reason, Instant at) {
        this(orderId, voidId, storeId, terminalId, orderNumber, tableCode, line, quantity, reason, at, null);
    }
}
