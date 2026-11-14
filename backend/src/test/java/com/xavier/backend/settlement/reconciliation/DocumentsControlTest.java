package com.xavier.backend.settlement.reconciliation;

import com.xavier.backend.OwnerDatabase;
import static com.xavier.backend.settlement.reconciliation.ReconciliationRequests.BASE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import com.xavier.backend.settlement.SettlementTestClock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D80, PRD S31: documents against payments of a store's business day, computed from the facts. A settled day has no
 * exception; a settled void's cent of tax (D96) is no exception once the residual absorbed it, a pending correction
 * while no document could, and a bug alarm when nothing records it; a stuck credit sale and its draft show after an
 * hour; a closed order that lost money it was invoiced for is an alarm.
 */
@SettlementModuleTest
class DocumentsControlTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @Autowired
    SettlementTestClock clock;


    @BeforeEach
    void aManagerApproves() {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
    }

    @AfterEach
    void reset() {
        Mockito.reset(fiscalAdapter);
        clock.reset();
    }

    @Test
    void aPaidAndClosedSaleHasNoException() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payAndClose(sale);

        JsonNode control = control(sale);

        assertThat(control.get("exceptions")).isEmpty();
        assertThat(control.get("roundingDifferences")).isEmpty();
    }

    @Test
    void theCentOfTaxTheResidualAbsorbedIsNoException() throws Exception {
        Sale sale = fixtures.sale("2"); // the line's tax 3.45; a partition of 1 takes 1.73; the void leaves 1.72
        UUID partition = partitionOneAndVoidTheOther(sale);
        fixtures.payPartition(sale.cashier(), sale.order(), partition, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk()); // the residual carries A's share, 1.72

        JsonNode control = control(sale);

        assertThat(control.get("exceptions")).isEmpty();
        assertThat(control.get("roundingDifferences")).isEmpty();
    }

    @Test
    void theCentOfTaxNoDocumentCouldTakeIsAPendingCorrection() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID document = invoicedAloneAndClosed(sale);

        JsonNode control = control(sale);

        assertThat(kinds(control)).containsExactly("TAX_CORRECTION_PENDING");
        assertThat(control.get("exceptions").get(0).get("documentId").asString()).isEqualTo(document.toString());
        assertThat(control.get("roundingDifferences")).isEmpty();
    }

    @Test
    void aClosedOrdersCentNoAdjustmentRecordsIsABugAlarm() throws Exception {
        Sale sale = fixtures.sale("2");
        invoicedAloneAndClosed(sale);
        // a bug's doing, written past the services and the fact trigger: the adjustment row is gone
        // (as the owner: disabling a trigger is DDL, which the pool may not run)
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("ALTER TABLE settlement.tax_rounding_adjustment DISABLE TRIGGER tax_rounding_adjustment_append_only")
                    .update();
            owner.sql("DELETE FROM settlement.tax_rounding_adjustment WHERE order_id = ?").param(sale.order()).update();
            owner.sql("ALTER TABLE settlement.tax_rounding_adjustment ENABLE TRIGGER tax_rounding_adjustment_append_only")
                    .update();
        });

        assertThat(kinds(control(sale))).containsExactly("TAX_DRIFT_UNASSIGNED");
    }

    private UUID partitionOneAndVoidTheOther(Sale sale) throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated()));
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());
        return partition;
    }

    /** The partition is invoiced on its own: no residual document, so the close records a pending correction. */
    private UUID invoicedAloneAndClosed(Sale sale) throws Exception {
        UUID partition = partitionOneAndVoidTheOther(sale);
        fixtures.payPartition(sale.cashier(), sale.order(), partition, "CASH", "12.50", "12.50",
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID document = SettlementFixtures.id(fixtures.partitionDocument(sale.cashier(), partition,
                UUID.randomUUID().toString()).andExpect(status().isCreated()));
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        return document;
    }

    @Test
    void aCreditSaleStuckForAnHourShowsItsChargeAndItsDraft() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID customer = customers.creditCustomer("100.00");
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.creditCharge(sale, customer, "25.00").andExpect(status().isUnprocessableContent());
        assertThat(kinds(control(sale))).isEmpty(); // a pendency, not stuck yet

        clock.advance(Duration.ofMinutes(61));

        assertThat(kinds(control(sale))).containsExactlyInAnyOrder("CHARGE_RESERVED_STALE", "DRAFT_STALE");
    }

    @Test
    void aClosedOrderThatLostMoneyItWasInvoicedForIsAnAlarm() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payAndClose(sale);
        UUID payment = fixtures.paymentOf(sale.order());
        // a bug's doing, written past the services: money left the order with no command
        jdbc.sql("""
                INSERT INTO settlement.refund (id, payment_id, store_id, target_type, order_id, method_code, amount,
                    currency, reason, session_id, terminal_id, business_date, operator_employee_id,
                    approver_employee_id, confirmed_at)
                SELECT ?, p.id, p.store_id, 'ORDER', p.order_id, p.method_code, 5.00, p.currency, 'bug', p.session_id,
                    p.terminal_id, p.business_date, ?, ?, now() FROM settlement.payment p WHERE p.id = ?""")
                .params(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), payment).update();

        JsonNode control = control(sale);

        assertThat(kinds(control)).containsExactly("DOCUMENT_UNPAID");
        assertThat(control.get("exceptions").get(0).get("orderId").asString()).isEqualTo(sale.order().toString());
    }

    @Test
    void aPostedChargeThatNoLongerMatchesItsDocumentsSnapshotIsAnAlarm() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.creditCharge(sale, customers.creditCustomer("100.00"), "25.00").andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(kinds(control(sale))).isEmpty();
        // a bug's doing, written past the services and the charge's trigger: the posted credit moved after issue
        // (as the owner: disabling a trigger is DDL, which the pool may not run)
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("ALTER TABLE settlement.credit_charge DISABLE TRIGGER credit_charge_immutable").update();
            owner.sql("UPDATE settlement.credit_charge SET amount = 20.00 WHERE order_id = ?").param(sale.order())
                    .update();
            owner.sql("ALTER TABLE settlement.credit_charge ENABLE TRIGGER credit_charge_immutable").update();
        });

        assertThat(kinds(control(sale))).containsExactlyInAnyOrder("SNAPSHOT_CREDIT_MISMATCH", "DOCUMENT_UNPAID");
    }

    @Test
    void needsReconciliationViewInTheStore() throws Exception {
        UUID store = UUID.randomUUID();
        mvc.perform(get(BASE + "/documents").param("storeId", store.toString())
                        .param("businessDate", LocalDate.now().toString())
                        .with(CashFixtures.account(store, Permission.PAYMENT_RESOLVE)))
                .andExpect(status().isForbidden());
    }

    private JsonNode control(Sale sale) throws Exception {
        LocalDate day = jdbc.sql("SELECT business_day FROM orders.order_header WHERE id = ?").param(sale.order())
                .query(LocalDate.class).single();
        return SettlementFixtures.json(mvc.perform(get(BASE + "/documents").param("storeId", sale.store().toString())
                        .param("businessDate", day.toString())
                        .with(CashFixtures.account(sale.store(), Permission.RECONCILIATION_VIEW)))
                .andExpect(status().isOk()));
    }

    private static List<String> kinds(JsonNode control) {
        List<String> kinds = new ArrayList<>();
        control.get("exceptions").forEach(e -> kinds.add(e.get("kind").asString()));
        return kinds;
    }
}
