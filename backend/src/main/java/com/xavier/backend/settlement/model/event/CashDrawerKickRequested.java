package com.xavier.backend.settlement.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A cash payment or cash refund was recorded on a terminal, so its drawer should open (D158, P59). Published in the
 * recording transaction (registry publication, D19); production kicks the terminal's receipt printer once per
 * source.
 *
 * @param sourceType {@code PAYMENT} or {@code REFUND}
 * @param sourceId   the payment or refund id (production's dedupe key)
 * @param storeId    the store of the payment or refund
 * @param terminalId the terminal it was recorded on
 */
@NamedInterface("event")
public record CashDrawerKickRequested(String sourceType, UUID sourceId, UUID storeId, UUID terminalId) {
}
