package com.xavier.backend.production.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D158, A66: {@code POST /pos/drawer-kicks {reason}} opens the session terminal's drawer for a cash movement (entry,
 * withdrawal, float): {@code CASH_OPERATE} on the POS (403 first, before the body is judged), a reason (422
 * {@code REASON_REQUIRED}), a receipt printer (422 {@code NO_RECEIPT_PRINTER}) with a drawer (422
 * {@code NO_CASH_DRAWER}); 201 with the kick's job, audited {@code DRAWER_OPENED_MANUALLY} with the reason; a
 * replay of the key kicks once.
 */
@ProductionModuleTest
class ManualDrawerKickTest {

    static final String DRAWER_KICKS = "/api/v1/pos/drawer-kicks";

    @Autowired
    MockMvc mvc;

    @Autowired
    DrawerKicks kicks;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCashierOpensTheDrawerWithAReasonAndItIsAudited() throws Exception {
        try (TcpFakePrinter fake = TcpFakePrinter.accepting()) {
            Till till = kicks.till(stores.newStore(), fake.host(), fake.port(), DrawerKicks.DRAWER);
            UUID employee = UUID.randomUUID();

            UUID job = ProductionFixtures.id(open(till, employee, "{\"reason\": \"  troco  \"}",
                    UUID.randomUUID().toString(), Permission.CASH_OPERATE)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.printerId").value(till.printer().toString()))
                    .andExpect(jsonPath("$.sourceType").value("DRAWER_KICK")));
            fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "kick sent");

            assertThat(fake.received()).singleElement().satisfies(bytes ->
                    assertThat(HexFormat.of().formatHex(bytes)).isEqualTo(DrawerKicks.KICK_BYTES));
            Map<String, Object> audit = jdbc.sql("""
                            SELECT * FROM audit.audit_event WHERE action = 'DRAWER_OPENED_MANUALLY' AND store_id = ?""")
                    .param(till.store()).query().singleRow();
            assertThat(audit).containsEntry("actor_id", employee).containsEntry("terminal_id", till.terminal())
                    .containsEntry("reason", "troco").containsEntry("entity_id", job.toString());
        }
    }

    @Test
    void aReplayOfTheKeyKicksOnce() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        fixtures.mode(till.store(), till.printer(), "HOLD");
        UUID employee = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        UUID first = ProductionFixtures.id(open(till, employee, "{\"reason\": \"sangria\"}", key,
                Permission.CASH_OPERATE).andExpect(status().isCreated()));
        UUID again = ProductionFixtures.id(open(till, employee, "{\"reason\": \"sangria\"}", key,
                Permission.CASH_OPERATE).andExpect(status().isCreated()));

        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE store_id = ? "
                + "AND source_type = 'DRAWER_KICK'", till.store())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'DRAWER_OPENED_MANUALLY' "
                + "AND store_id = ?", till.store())).isEqualTo(1);
    }

    @Test
    void withoutCashOperateItIsRefusedBeforeTheBodyIsJudged() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);

        open(till, UUID.randomUUID(), "{\"reason\": \" \"}", UUID.randomUUID().toString(), Permission.ORDER_EDIT)
                .andExpect(status().isForbidden());

        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE store_id = ?", till.store()))
                .isZero();
    }

    @Test
    void aReasonIsRequired() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);

        open(till, UUID.randomUUID(), "{\"reason\": \"  \"}", UUID.randomUUID().toString(), Permission.CASH_OPERATE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        open(till, UUID.randomUUID(), "{}", UUID.randomUUID().toString(), Permission.CASH_OPERATE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));

        assertThat(count("SELECT count(*) FROM production.production_ticket WHERE store_id = ?", till.store()))
                .isZero();
    }

    @Test
    void aTerminalWithoutAPrinterHasNoDrawerToOpen() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);

        open(new Till(till.store(), UUID.randomUUID(), till.printer()), UUID.randomUUID(),
                "{\"reason\": \"troco\"}", UUID.randomUUID().toString(), Permission.CASH_OPERATE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_RECEIPT_PRINTER"));
    }

    @Test
    void aPrinterWithoutADrawerHasNoDrawerToOpen() throws Exception {
        Till till = kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), "\"feedLines\": 4");

        open(till, UUID.randomUUID(), "{\"reason\": \"troco\"}", UUID.randomUUID().toString(),
                Permission.CASH_OPERATE)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_CASH_DRAWER"));
        assertThat(count("SELECT count(*) FROM audit.audit_event WHERE action = 'DRAWER_OPENED_MANUALLY' "
                + "AND store_id = ?", till.store())).isZero();
    }

    private ResultActions open(Till till, UUID employee, String body, String key, Permission... permissions)
            throws Exception {
        return mvc.perform(post(DRAWER_KICKS).with(OrdersActors.pos(till.store(), employee, till.terminal(),
                        permissions)).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String sql, UUID store) {
        return jdbc.sql(sql).param(store).query(Long.class).single();
    }
}
