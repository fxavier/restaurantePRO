package com.xavier.backend.cash.service;

import com.xavier.backend.shared.persistence.StoreProbe;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * D42 under row-level security (D168): the store of a cash resource, read across every store through
 * {@code shared.store_of}, so another store's register, day, session or closing is found (then 403), never mistaken
 * for absent (404) as a read under the actor's scope would. Empty when the resource does not exist.
 */
@Component
class CashStoreProbe {

    private final StoreProbe probe;

    CashStoreProbe(StoreProbe probe) {
        this.probe = probe;
    }

    Optional<UUID> register(UUID id) {
        return probe.storeOf("cash.cash_register", id);
    }

    Optional<UUID> day(UUID id) {
        return probe.storeOf("cash.cash_day", id);
    }

    Optional<UUID> session(UUID id) {
        return probe.storeOf("cash.cash_session", id);
    }

    Optional<UUID> closing(UUID id) {
        return probe.storeOf("cash.cash_closing", id);
    }
}
