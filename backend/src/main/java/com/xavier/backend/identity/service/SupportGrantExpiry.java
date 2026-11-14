package com.xavier.backend.identity.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Each minute ({@code restaurantpro.identity.support-expiry-cron}, {@code -} disables it), expires the support grants whose end has come and ends their sessions (D83). Time is the injected
 * {@code Clock}'s. Each grant is its own transaction, and expiring is a compare-and-set, so an overlapping run or a
 * revoke that got there first changes nothing. Login already refuses a grant past its end before this job runs; the
 * job is what ends the sessions that are already open (within a minute, r3-decisions §15).
 */
@Service
public class SupportGrantExpiry {

    private static final Logger log = LoggerFactory.getLogger(SupportGrantExpiry.class);

    private final SupportGrantService grants;

    SupportGrantExpiry(SupportGrantService grants) {
        this.grants = grants;
    }

    /**
     * A grant that cannot be expired is logged and left for the next run; it does not hold the others back (their
     * sessions are also refused on their next request by {@code SupportSessionFilter}).
     *
     * @return how many grants this run expired
     */
    @SystemScoped(SystemScope.IDENTITY_MAINTENANCE)
    @Scheduled(cron = "${restaurantpro.identity.support-expiry-cron:0 * * * * *}")
    public int expireDue() {
        int expired = 0;
        for (UUID grantId : grants.dueGrantIds()) {
            try {
                expired += grants.expire(grantId) ? 1 : 0;
            } catch (RuntimeException e) {
                log.error("Support grant {} could not be expired; the next run retries it", grantId, e);
            }
        }
        if (expired > 0) {
            log.info("Support grant expiry ended {} grants", expired);
        }
        return expired;
    }
}
