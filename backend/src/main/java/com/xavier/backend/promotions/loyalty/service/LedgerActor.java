package com.xavier.backend.promotions.loyalty.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import java.util.UUID;

/** Who a ledger entry is recorded for: the session's actor, or SYSTEM (no id) outside a session. */
record LedgerActor(String kind, UUID id) {

    static final String SYSTEM = "SYSTEM";

    static LedgerActor of(AccessPolicy accessPolicy) {
        return accessPolicy.currentIfPresent().map(LedgerActor::of).orElse(new LedgerActor(SYSTEM, null));
    }

    private static LedgerActor of(Actor actor) {
        return new LedgerActor(actor.kind().name(), actor.userId());
    }
}
