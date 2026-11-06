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
 * @param creditSweepGrace a RESERVED credit charge older than this whose document is ISSUED lost its sale's last step
 *                         (D74): the credit sweeper posts it; a younger one is a sale still running its steps
 * @param inDoubtPollInterval    how often the {@link InDoubtResolver} looks for UNKNOWN attempts due a poll (D81)
 * @param reclassifyFailedMinAge a manual reclassification to FAILED needs the attempt older than this, and an
 *                               answered provider status query since it became UNKNOWN (D81 (3))
 * @param reclassifyFailedWithoutProviderMinAge the same for a method whose provider bean is gone (it can never be
 *                               answered): only this age guards it
 * @param inDoubtPollBatch       at most this many attempts per resolver run, so slow providers never hold the
 *                               scheduler for long
 */
@ConfigurationProperties("restaurantpro.settlement")
public record SettlementProperties(@DefaultValue("PT10S") Duration providerTimeout,
                                   @DefaultValue("PT5S") Duration sweepInterval,
                                   @DefaultValue("PT10S") Duration createdGrace,
                                   @DefaultValue("PT30S") Duration pendingMargin,
                                   @DefaultValue("4") int providerThreads,
                                   @DefaultValue("PT1M") Duration creditSweepGrace,
                                   @DefaultValue("PT30S") Duration inDoubtPollInterval,
                                   @DefaultValue("PT15M") Duration reclassifyFailedMinAge,
                                   @DefaultValue("PT24H") Duration reclassifyFailedWithoutProviderMinAge,
                                   @DefaultValue("20") int inDoubtPollBatch) {
}
