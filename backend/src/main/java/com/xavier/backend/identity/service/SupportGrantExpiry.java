package com.xavier.backend.identity.service;

import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Each minute, expires the support grants whose end has come and ends their sessions (D83). Time is the injected
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

    /** @return how many grants this run expired */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public int expireDue() {
        int expired = (int) grants.dueGrantIds().stream().filter(grants::expire).count();
        if (expired > 0) {
            log.info("Support grant expiry ended {} grants", expired);
        }
        return expired;
    }
}
