package com.xavier.backend.settlement.reconciliation;

import com.xavier.backend.OwnerDatabase;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.confirmedAt;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.entries;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.importCsv;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.json;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.line;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.resolve;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D78: reconciliation never creates, edits or deletes a Payment, Refund or CashMovement (nor an attempt or a credit
 * charge). Proven in the database: while the test imports statements and resolves every entry, a trigger on each of
 * those tables logs any INSERT, UPDATE or DELETE of the test's store, and the rows' fingerprint (count and an md5 of
 * every column) is compared before and after.
 */
@SettlementModuleTest
class ReconciliationNeverWritesMoneyTest {

    static final List<String> MONEY_TABLES = List.of("settlement.payment", "settlement.refund", "cash.cash_movement",
            "settlement.payment_attempt", "settlement.credit_charge");

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApproverVerifier approvers;

    Sale sale;
    String card;

    @BeforeEach
    void cardPaymentsCashAndARefund() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.PAYMENT_REFUND_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
        sale = fixtures.sale("4"); // 50.00
        card = cardMethod(fixtures);
    }

    /** The probe is DDL, which the pool may not run: the owner installs and removes it. */
    final JdbcClient owner = OwnerDatabase.shared().jdbc();

    @AfterEach
    void removeTheProbe() {
        for (String table : MONEY_TABLES) {
            owner.sql("DROP TRIGGER IF EXISTS reconciliation_write_probe ON " + table).update();
        }
        owner.sql("DROP FUNCTION IF EXISTS public.reconciliation_write_probe()").update();
        owner.sql("DROP TABLE IF EXISTS public.reconciliation_write_probe_log").update();
    }

    @Test
    void importingAndResolvingWritesNoMoneyFact() throws Exception {
        UUID matched = cardPayment(fixtures, sale, card, "20.00", "A1");
        UUID refunded = cardPayment(fixtures, sale, card, "15.00", "A2");
        fixtures.refund(sale.cashier(), refunded, "5.00", "RF-9", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        cardPayment(fixtures, sale, card, "10.00", "A3"); // the statement lacks it
        fixtures.payCash(sale, "5.00", "5.00").andExpect(status().isCreated());
        Instant at = confirmedAt(jdbc, matched);
        Map<String, String> before = fingerprints();
        installProbe();

        String statement = line(at, "20.00", "0.40", null, "A1", "L1") + line(at, "15.00", "0.10", null, "A2", "L2")
                + line(at, "99.00", "0", null, "Z9", "L3");
        UUID batch = UUID.fromString(json(importCsv(mvc, sale.store(), card, statement, UUID.randomUUID().toString())
                .andExpect(status().isCreated())).get("id").asString());
        importCsv(mvc, sale.store(), card, statement + line(at, "20.00", "0", null, "A1", "L4"),
                UUID.randomUUID().toString()).andExpect(status().isCreated()); // a duplicate line, a later batch
        String[] actions = {"ACCEPTED", "REFUNDED_AT_PROVIDER", "ESCALATED"};
        int i = 0;
        for (JsonNode entry : json(entries(mvc, sale.store(), batch, "?open=true").andExpect(status().isOk()))
                .get("content")) {
            resolve(mvc, sale.store(), UUID.fromString(entry.get("id").asString()), actions[i++ % 3], "checked")
                    .andExpect(status().isCreated());
        }

        assertThat(i).as("open entries resolved").isEqualTo(3);
        assertThat(probedWrites()).isEmpty();
        assertThat(fingerprints()).isEqualTo(before);
        assertThat(fixtures.count("SELECT count(*) FROM settlement.reconciliation_entry WHERE store_id = ?",
                sale.store())).isPositive();
    }

    /** The probe is red-capable: an attempt row touched in the store is logged. */
    @Test
    void theProbeSeesAWriteToAMoneyTable() throws Exception {
        UUID payment = cardPayment(fixtures, sale, card, "20.00", "A1");
        installProbe();

        jdbc.sql("UPDATE settlement.payment_attempt SET updated_at = updated_at WHERE id = "
                + "(SELECT attempt_id FROM settlement.payment WHERE id = ?)").param(payment).update();

        assertThat(probedWrites()).containsExactly("settlement.payment_attempt UPDATE");
    }

    private void installProbe() {
        owner.sql("CREATE TABLE public.reconciliation_write_probe_log (tbl TEXT, op TEXT, store_id UUID)").update();
        owner.sql("""
                CREATE FUNCTION public.reconciliation_write_probe() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO public.reconciliation_write_probe_log
                    VALUES (TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME, TG_OP,
                            CASE WHEN TG_OP = 'DELETE' THEN OLD.store_id ELSE NEW.store_id END);
                    RETURN NULL;
                END $$""").update();
        for (String table : MONEY_TABLES) {
            owner.sql("CREATE TRIGGER reconciliation_write_probe AFTER INSERT OR UPDATE OR DELETE ON " + table
                    + " FOR EACH ROW EXECUTE FUNCTION public.reconciliation_write_probe()").update();
        }
    }

    private List<String> probedWrites() {
        return jdbc.sql("SELECT tbl || ' ' || op FROM public.reconciliation_write_probe_log WHERE store_id = ?")
                .param(sale.store()).query(String.class).list();
    }

    /** Per money table: the store's row count and an md5 over every column of its rows. */
    private Map<String, String> fingerprints() {
        Map<String, String> prints = new java.util.TreeMap<>();
        for (String table : MONEY_TABLES) {
            prints.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t.id)),"
                    + " '') FROM " + table + " t WHERE t.store_id = ?").param(sale.store()).query(String.class)
                    .single());
        }
        return prints;
    }
}
