package com.xavier.backend.settlement.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A cash payment or cash refund was recorded on a terminal, so its drawer should open (D158, P59). Published in the
 * recording transaction (registry publication, D19); production kicks the terminal's receipt printer once per
 * source, and not later than 30 s after {@code requestedAt}.
 *
 * @param sourceType  {@code PAYMENT} or {@code REFUND}
 * @param sourceId    the payment or refund id (production's dedupe key)
 * @param storeId     the store of the payment or refund
 * @param terminalId  the terminal it was recorded on
 * @param requestedAt when the payment or refund was recorded (D158 amended, S60 review); {@code null} in a
 *                    publication stored before it existed, which never kicks
 */
@NamedInterface("event")
public record CashDrawerKickRequested(String sourceType, UUID sourceId, UUID storeId, UUID terminalId,
                                      Instant requestedAt) {
}
