package com.xavier.backend.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.organization.OrganizationModuleTest;
import com.xavier.backend.organization.model.dto.StoreCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalCreateRequest;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import com.xavier.backend.organization.model.event.TerminalRevoked;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.AssertablePublishedEvents;

/** Identity ends the POS sessions of a revoked terminal; it learns about the revocation from this event. */
@OrganizationModuleTest
@WithActor(permissions = {Permission.STORE_MANAGE, Permission.TERMINAL_MANAGE})
class TerminalRevokedTest {

    @Autowired
    StoreService stores;

    @Autowired
    TerminalService terminals;

    @Autowired
    MutableClock clock;

    @Test
    void revokingATerminalPublishesTerminalRevoked(AssertablePublishedEvents events) {
        UUID store = stores.create(new StoreCreateRequest("R" + UUID.randomUUID().toString().substring(0, 8),
                "Store", "Africa/Maputo", null)).id();
        TerminalSummary terminal = terminals.create(new TerminalCreateRequest(store, "POS1", "Bar"));
        terminals.enroll(terminals.issueEnrollmentCode(terminal.id()).code(), "192.0.2.1");

        terminals.revoke(terminal.id());

        assertThat(events.ofType(TerminalRevoked.class)
                .matching(e -> e.terminalId().equals(terminal.id()) && e.storeId().equals(store)
                        && e.revokedAt().equals(clock.instant())))
                .hasSize(1);
    }
}
