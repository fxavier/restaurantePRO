package com.xavier.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.repository.BalanceDrift;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * D185 (fairness): a reconcile run lists at most {@code restaurantpro.jobs.batch-per-tenant} drifts of a tenant in the
 * log, so one damaged tenant cannot flood it; the drift count, the stored check and the returned list stay complete.
 */
@InventoryModuleTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "restaurantpro.jobs.batch-per-tenant=2")
class StockBalanceReconciliationCapTest {

    static final UUID TENANT = UUID.fromString("0190b300-0000-7000-8000-0000000074c3");

    @Autowired
    StockBalanceReconciliationJob job;

    @Autowired
    TenantScopes tenants;

    @Autowired
    MeterRegistry meters;

    @Test
    void aRunListsAtMostTheBatchOfDriftsButCountsThemAll(CapturedOutput output) {
        TenantTestSupport.ensureTenant(TENANT, "reconcile-cap", "ACTIVE");
        UUID store = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(TENANT).allStores()) {
            app.jdbc().sql("""
                            INSERT INTO inventory.warehouse (id, store_id, code, name, active, version, created_at,
                                updated_at) VALUES (?, ?, 'MAIN', 'Main', true, 0, now(), now())""")
                    .params(warehouse, store).update();
            for (int i = 1; i <= 3; i++) {
                UUID item = UUID.randomUUID();
                app.jdbc().sql("""
                                INSERT INTO inventory.stock_item (id, code, name, base_unit, kind, active, version,
                                    created_at, updated_at) VALUES (?, ?, 'Item', 'kg', 'PURCHASED', true, 0, now(),
                                    now())""").params(item, "CAP-" + i).update();
                app.jdbc().sql("""
                                INSERT INTO inventory.stock_balance (warehouse_id, store_id, stock_item_id, quantity,
                                    value, average_cost, last_posting_seq, updated_at)
                                VALUES (?, ?, ?, 1, 2, 2, 0, now())""").params(warehouse, store, item).update();
            }
        }

        List<BalanceDrift> drifts = TenantTestSupport.inTenant(tenants, TENANT, job::reconcile);

        assertThat(drifts).as("the result is complete").hasSize(3);
        assertThat(output.getAll().lines().filter(line -> line.contains("Stock balance drift in store " + store)))
                .as("listed").hasSize(2);
        assertThat(output.getAll()).contains("1 more stock balance drifts of tenant " + TENANT);
        assertThat(meters.get("inventory_balance_drift").gauge().value()).as("counted").isGreaterThanOrEqualTo(3.0);
    }
}
