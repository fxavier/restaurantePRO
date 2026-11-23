package com.xavier.backend.settlement.model.dto;

import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What the POS warns about in the session's store (light reference: "1 pagamento por confirmar").
 *
 * @param paymentsToConfirm the payment attempts in doubt (UNKNOWN, §7.4): never failed, a person confirms them
 * @param oldestOrderId     the order of the oldest of them that has one, whose payment screen resolves it; {@code null}
 *                          when none is in doubt or only debt payments (no order) are
 */
@NullMarked
public record SettlementAlertsView(long paymentsToConfirm, @Nullable UUID oldestOrderId) {
}
