package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D71, P32, A24: the settled void works under the PAYMENT lock within headroom: the quantity is in no LIVE partition
 * and on no document, and the residual stays at or above what its target was paid or has in flight.
 */
@SettlementModuleTest
class SettledVoidHeadroomTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    com.xavier.backend.customers.CustomersFixtures customers;

    @BeforeEach
    void aManagerApproves() {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(MANAGER);
    }

    @Test
    void aVoidAfterAPartialPaymentWithinHeadroomIsRecorded() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated()); // the order is PAYMENT-locked
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(1))
                .andExpect(jsonPath("$.approverEmployeeId").value(MANAGER.toString()))
                .andExpect(jsonPath("$.order.due.amount").value(12.50));

        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line_void WHERE line_id = ?", line))
                .isEqualTo(1);
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.balance.amount").value(2.50));
    }

    @Test
    void aVoidBeyondWhatIsUnpaidIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "20.00", "20.00").andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VOID_EXCEEDS_UNPAID"));
    }

    @Test
    void anOpenAttemptCountsAgainstTheHeadroom() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        fixtures.pay(sale.cashier(), sale.order(), card, "20.00", null, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VOID_EXCEEDS_UNPAID"));
    }

    @Test
    void anOrderOnAccountIsNotVoided() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        fixtures.creditCharge(sale, customers.creditCustomer("100.00"), "20.00").andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString()) // the credit sale drafted the order's document
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line_void WHERE line_id = ?", line)).isZero();
    }

    @Test
    void aQuantityInALivePartitionIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString(), line, "2").andExpect(status().isCreated());

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_PARTITIONED"));
    }

    @Test
    void anInvoicedQuantityNeedsTheFiscalAdapter() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"))
                .get("id").asString());
        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "2", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString()) // the quantity on no document can still be voided
                .andExpect(status().isCreated());
    }

    @Test
    void aFrozenOrderNeedsTheFiscalAdapter() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
    }

    @Test
    void aVoidThatEmptiesAPartitionedLinesPoolShowsTheCentOfTaxTheDocumentsDoNotCarry() throws Exception {
        Sale sale = fixtures.sale("2"); // the line's tax 3.45; a partition of 1 takes 1.73 (L(2) - L(1))
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString(), line, "1").andExpect(status().isCreated());

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                "1", UUID.randomUUID().toString()).andExpect(status().isCreated());

        // the order now recomputes 1 x 12.50 with tax 1.72; the documents will carry the partition's 1.73
        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.taxRoundingDifference[0].ratePercent").value(16))
                .andExpect(jsonPath("$.taxRoundingDifference[0].difference.amount").value(-0.01));
    }

    @Test
    void anOrderWithoutPartitionsHasNoTaxRoundingDifference() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settlement(sale.cashier(), sale.order())
                .andExpect(jsonPath("$.taxRoundingDifference[0].difference.amount").value(0));
    }

    @Test
    void theOperatorCannotApproveTheirOwnVoid() throws Exception {
        Sale sale = fixtures.sale("2");
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any()))
                .thenReturn(sale.cashier().employeeId());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), line,
                        "1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
    }
}
