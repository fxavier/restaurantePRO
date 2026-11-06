package com.xavier.backend.settlement.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code restaurantpro.settlement.*} (D48).
 *
 * @param providerTimeout  how long one provider call may take before it counts as UNKNOWN; must stay below
 *                         {@code restaurantpro.idempotency.step-lease}, because a resolve waits for it inside a D63
 *                         step (checked at startup by {@link ProviderCalls})
 * @param sweepInterval    how often the sweeper runs
 * @param createdGrace     a CREATED integrated attempt older than this was never submitted: the sweeper submits it
 * @param pendingMargin    a PENDING integrated attempt older than {@code providerTimeout + pendingMargin} lost its
 *                         caller (a crash during the call): the sweeper makes it UNKNOWN
 * @param providerThreads  threads (and the bound of each queue) of the submitter and of the provider calls
 */
@ConfigurationProperties("restaurantpro.settlement")
public record SettlementProperties(@DefaultValue("PT10S") Duration providerTimeout,
                                   @DefaultValue("PT5S") Duration sweepInterval,
                                   @DefaultValue("PT10S") Duration createdGrace,
                                   @DefaultValue("PT30S") Duration pendingMargin,
                                   @DefaultValue("4") int providerThreads) {
}
