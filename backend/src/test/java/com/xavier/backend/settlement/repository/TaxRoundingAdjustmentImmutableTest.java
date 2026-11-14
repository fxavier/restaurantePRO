package com.xavier.backend.settlement.repository;

import com.xavier.backend.OwnerDatabase;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V9050–V9051 (D96 rules 5–6): a tax rounding adjustment and its correction resolution are facts. The database refuses
 * UPDATE, DELETE and TRUNCATE on both, one row per drifting line, one resolution per adjustment, an ABSORBED row names
 * its target line, and only a PENDING_CORRECTION row can be resolved.
 */
@SettlementModuleTest
class TaxRoundingAdjustmentImmutableTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void anAdjustmentIsAppendOnly() {
        UUID id = adjustment(UUID.randomUUID(), UUID.randomUUID(), "PENDING_CORRECTION", null);

        assertRefused("UPDATE settlement.tax_rounding_adjustment SET amount = 0.02 WHERE id = '" + id + "'");
        assertRefused("DELETE FROM settlement.tax_rounding_adjustment WHERE id = '" + id + "'");
        assertRefused("TRUNCATE settlement.tax_rounding_adjustment CASCADE");
    }

    @Test
    void aResolutionIsAppendOnly() {
        UUID adjustment = adjustment(UUID.randomUUID(), UUID.randomUUID(), "PENDING_CORRECTION", null);
        UUID id = resolution(adjustment, "NC-1");

        assertRefused("UPDATE settlement.tax_correction_resolution SET reason = 'x' WHERE id = '" + id + "'");
        assertRefused("DELETE FROM settlement.tax_correction_resolution WHERE id = '" + id + "'");
        assertRefused("TRUNCATE settlement.tax_correction_resolution");
    }

    @Test
    void oneRowPerDriftingLineAndAnAbsorbedRowNamesItsTarget() {
        UUID order = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        adjustment(order, line, "ABSORBED", UUID.randomUUID());

        assertThatThrownBy(() -> adjustment(order, line, "PENDING_CORRECTION", null))
                .hasMessageContaining("tax_rounding_adjustment_line_uk");
        assertThatThrownBy(() -> adjustment(UUID.randomUUID(), UUID.randomUUID(), "ABSORBED", null))
                .hasMessageContaining("tax_rounding_adjustment_target_ck");
        assertThatThrownBy(() -> adjustment(UUID.randomUUID(), UUID.randomUUID(), "WRITTEN_OFF", null))
                .hasMessageContaining("tax_rounding_adjustment_kind_ck");
    }

    @Test
    void onlyAPendingCorrectionIsResolvedOnceWithAReference() {
        UUID pending = adjustment(UUID.randomUUID(), UUID.randomUUID(), "PENDING_CORRECTION", null);
        UUID absorbed = adjustment(UUID.randomUUID(), UUID.randomUUID(), "ABSORBED", UUID.randomUUID());

        assertThatThrownBy(() -> resolution(pending, " ")).hasMessageContaining("tax_correction_resolution_reference_ck");
        assertThatCode(() -> resolution(pending, "NC-1")).doesNotThrowAnyException();
        assertThatThrownBy(() -> resolution(pending, "NC-2"))
                .hasMessageContaining("tax_correction_resolution_adjustment_uk");
        assertThatThrownBy(() -> resolution(absorbed, "NC-3")).hasMessageContaining("PENDING_CORRECTION");
    }

    /** {@code TRUNCATE} as the owner: the pool has no TRUNCATE privilege, so only the trigger can stop the owner. */
    private void assertRefused(String sql) {
        Runnable statement = sql.startsWith("TRUNCATE")
                ? () -> OwnerDatabase.shared().rolledBack(owner -> owner.sql(sql).update())
                : () -> jdbc.sql(sql).update();
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    private UUID adjustment(UUID order, UUID line, String kind, UUID targetLine) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.tax_rounding_adjustment (id, order_id, store_id, business_date, source_line_id,
                    rate_percent, amount, currency, kind, document_id, target_line_id, target_partition_id,
                    actor_kind, actor_id, created_at)
                VALUES (?, ?, ?, DATE '2026-11-06', ?, 23, 0.01, 'MZN', ?, ?, ?, NULL, 'EMPLOYEE', ?, now())""")
                .params(id, order, UUID.randomUUID(), line, kind, UUID.randomUUID(), targetLine, UUID.randomUUID())
                .update();
        return id;
    }

    private UUID resolution(UUID adjustment, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO settlement.tax_correction_resolution (id, adjustment_id, action, correction_reference,
                    reason, actor_kind, actor_id, at)
                VALUES (?, ?, 'CORRECTED', ?, 'credit note issued', 'ACCOUNT', ?, now())""")
                .params(id, adjustment, reference, UUID.randomUUID()).update();
        return id;
    }
}
