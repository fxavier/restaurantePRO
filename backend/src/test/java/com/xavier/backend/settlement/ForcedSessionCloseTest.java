package com.xavier.backend.settlement;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * P22, D79 (4), A26 (forced-close half): a manager closes, on a POS of the store, a cash session its operator
 * abandoned, with the blind count and a reason. The session records the closer and {@code close_kind = FORCED}, and
 * the close is audited with the reason. A payment attempt still open or in doubt in that session blocks it (§8.4).
 */
@SettlementModuleTest
class ForcedSessionCloseTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    MockMvc mvc;

    Sale sale;
    Pos manager;

    @BeforeEach
    void anAbandonedSessionWithACashSale() throws Exception {
        sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        manager = CashFixtures.pos(sale.store());
    }

    @Test
    void aManagerForceClosesAnAbandonedSessionWithACountAndAReason() throws Exception {
        forceClose(manager, sale.session(), version(), "24.00", "shift ended without a close")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(fixtures.count("SELECT count(*) FROM cash.cash_session WHERE id = ? AND close_kind = 'FORCED' "
                + "AND closed_by_employee_id = ? AND close_reason = 'shift ended without a close'", sale.session(),
                manager.employeeId())).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'CASH_SESSION_FORCE_CLOSED' "
                + "AND entity_id = ? AND actor_id = ? AND reason = 'shift ended without a close'",
                sale.session().toString(), manager.employeeId())).isEqualTo(1);
    }

    @Test
    void aPaymentInDoubtInTheSessionBlocksTheForcedClose() throws Exception {
        Sale other = fixtures.sale("1");
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        UUID attempt = SettlementFixtures.id(fixtures.pay(other.cashier(), other.order(), card, "12.50", null,
                UUID.randomUUID().toString()).andExpect(status().isCreated()));

        forceClose(CashFixtures.pos(other.store()), other.session(),
                fixtures.session(other.cashier(), other.session()).get("version").asLong(), "0", "gone home")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_DOUBT"))
                .andExpect(jsonPath("$.attemptId").value(attempt.toString()));
        assertThat(fixtures.session(other.cashier(), other.session()).get("status").asString()).isEqualTo("OPEN");
    }

    @Test
    void aReasonIsRequired() throws Exception {
        forceClose(manager, sale.session(), version(), "25.00", " ").andExpect(status().isBadRequest());
    }

    @Test
    void withoutThePermissionItIsForbidden() throws Exception {
        mvc.perform(request(sale.session(), version(), "25.00", "gone home")
                        .with(manager.with(Permission.CASH_OPERATE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSessionOfAnotherStoreIsForbiddenAndAnUnknownOneNotFound() throws Exception {
        Pos elsewhere = CashFixtures.pos(fixtures.sale("1").store());

        forceClose(elsewhere, sale.session(), version(), "25.00", "gone home").andExpect(status().isForbidden());
        forceClose(manager, UUID.randomUUID(), 0, "25.00", "gone home").andExpect(status().isNotFound());
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        forceClose(manager, sale.session(), version() + 1, "25.00", "gone home").andExpect(status().isConflict());
    }

    private long version() throws Exception {
        return fixtures.session(sale.cashier(), sale.session()).get("version").asLong();
    }

    private ResultActions forceClose(Pos who, UUID session, long version, String cash, String reason)
            throws Exception {
        return mvc.perform(request(session, version, cash, reason).with(who.with(Permission.CASH_SESSION_FORCE_CLOSE)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            UUID session, long version, String cash, String reason) {
        return idem(post(CashFixtures.SESSIONS + "/" + session + "/force-close"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": %d, \"counts\": [{\"methodCode\": \"CASH\", \"amount\": %s}], \"reason\": \"%s\"}"
                        .formatted(version, cash, reason));
    }
}
