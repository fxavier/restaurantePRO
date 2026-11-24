package com.xavier.backend.production.model.dto;

import org.jspecify.annotations.NullMarked;

/**
 * What the POS warns about in the session's store (light reference: "1 impressão pendente", "Cozinha: 2 rondas >
 * 15 min").
 *
 * @param pendingPrintJobs     print jobs still waiting on a printer in NORMAL mode (one on HOLD waits on purpose), or
 *                             FAILED/UNKNOWN in the last 24 h without a retry or an acknowledgement; drawer kicks are
 *                             not counted
 * @param delayedKitchenRounds rounds with a ticket on the kitchen display, any station, not READY yet and queued more
 *                             than {@code kitchenDelayMinutes} ago; a round sent to two stations counts once
 * @param kitchenDelayMinutes  the delay after which a round counts as late ({@code restaurantpro.production.kitchen-delay})
 */
@NullMarked
public record ProductionAlertsView(long pendingPrintJobs, long delayedKitchenRounds, long kitchenDelayMinutes) {
}
