package com.xavier.backend.settlement.reconciliation;

import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardMethod;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.cardPayment;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V9040: the database holds every reconciliation invariant it can express, whatever the code does: facts are
 * append-only, a payment is matched once across batches, each kind has its shape, and only the system auto-matches.
 */
@SettlementModuleTest
class ReconciliationSchemaTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID payment;
    UUID batch;

    @BeforeEach
    void aBatchAndAPayment() throws Exception {
        Sale sale = fixtures.sale("2");
        store = sale.store();
        payment = cardPayment(fixtures, sale, cardMethod(fixtures), "25.00", "A1");
        batch = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.reconciliation_batch (id, store_id, method_code, period_from, period_to,
                    currency, file_sha256, source, line_count, imported_by_account_id, imported_at)
                VALUES (?, ?, 'CARD', DATE '2026-11-05', DATE '2026-11-05', 'MZN', ?, 'CSV', 2, ?, now())""")
                .params(batch, store, sha(), UUID.randomUUID()).update();
    }

    @Test
    void aPaymentIsMatchedOnceAcrossEveryBatch() {
        UUID first = entry(1, "MATCHED", payment, "25.00", "25.00");

        assertThatThrownBy(() -> entry(2, "AMOUNT_MISMATCH", payment, "24.00", "25.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("reconciliation_entry_payment_uk");
        assertThatCode(() -> entry(2, "DUPLICATE_LINE", payment, "25.00", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.sql("UPDATE settlement.reconciliation_entry SET fee_amount = 1 WHERE id = ?")
                .param(first).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM settlement.reconciliation_batch WHERE id = ?").param(batch)
                .update()).isInstanceOf(DataAccessException.class);
    }

    @Test
    void eachKindHasItsShape() {
        assertThatThrownBy(() -> entry(1, "MATCHED", payment, "24.00", "25.00"))
                .hasMessageContaining("reconciliation_entry_shape_ck");
        assertThatThrownBy(() -> entry(1, "MISSING_INTERNAL", payment, "24.00", null))
                .hasMessageContaining("reconciliation_entry_shape_ck");
        assertThatThrownBy(() -> entry(1, "MISSING_PROVIDER", payment, null, "25.00"))
                .hasMessageContaining("reconciliation_entry_line_ck");
    }

    @Test
    void onlyTheSystemAutoMatchesAndAPersonGivesAReason() {
        UUID matched = entry(1, "MATCHED", payment, "25.00", "25.00");

        assertThatThrownBy(() -> resolution(matched, "AUTO_MATCHED", null, "ACCOUNT", UUID.randomUUID()))
                .hasMessageContaining("reconciliation_resolution_actor_ck");
        assertThatThrownBy(() -> resolution(matched, "ACCEPTED", " ", "ACCOUNT", UUID.randomUUID()))
                .hasMessageContaining("reconciliation_resolution_actor_ck");
        resolution(matched, "AUTO_MATCHED", null, "SYSTEM", null);
        assertThatThrownBy(() -> resolution(matched, "ESCALATED", "again", "ACCOUNT", UUID.randomUUID()))
                .hasMessageContaining("reconciliation_resolution_entry_uk");
    }

    private UUID entry(Integer lineNo, String kind, UUID paymentId, String provider, String internal) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.reconciliation_entry (id, batch_id, line_no, store_id, kind, payment_id,
                    occurred_at, internal_amount, provider_amount, fee_amount, created_at)
                VALUES (?, ?, ?, ?, ?, ?, now(), ?::numeric, ?::numeric, 0, now())""")
                .params(id, batch, lineNo, store, kind, paymentId, internal, provider).update();
        return id;
    }

    private void resolution(UUID entry, String action, String reason, String actorKind, UUID actor) {
        jdbc.sql("""
                INSERT INTO settlement.reconciliation_resolution (id, entry_id, action, reason, actor_kind, actor_id, at)
                VALUES (?, ?, ?, ?, ?, ?, now())""").params(UUID.randomUUID(), entry, action, reason, actorKind, actor)
                .update();
    }

    private static String sha() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }
}
