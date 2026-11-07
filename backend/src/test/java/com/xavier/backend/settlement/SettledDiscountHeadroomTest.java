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
 * D105, A40 (settlement route): under the PAYMENT lock a discount goes through settlement, which holds
 * {@code order_settlement}: no quantity of the line in a LIVE partition, none on a document, and the ORDER residual
 * after the discount at or above what its target was paid or has in flight; then orders records it. The sale is
 * 2 × 12.50 = 25.00; no promotion release is published, so every discount needs the approver (0 % free, D106).
 */
@SettlementModuleTest
class SettledDiscountHeadroomTest {

    static final UUID MANAGER = UUID.randomUUID();

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @BeforeEach
    void aManagerApproves() {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any())).thenReturn(MANAGER);
    }

    @Test
    void aDiscountAfterAPartialPaymentWithinHeadroomIsRecorded() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated()); // the order is PAYMENT-locked
        UUID line = line(sale);

        fixtures.percentOff(sale, line, null, "50").andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(12.50))
                .andExpect(jsonPath("$.paid.amount").value(10.00))
                .andExpect(jsonPath("$.balance.amount").value(2.50));

        assertThat(adjustments(sale.order())).isOne();
    }

    @Test
    void aDiscountDownToExactlyWhatWasPaidIsRecorded() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "12.50", "12.50").andExpect(status().isCreated());

        fixtures.percentOff(sale, line(sale), null, "50").andExpect(status().isOk())
                .andExpect(jsonPath("$.balance.amount").value(0));
    }

    @Test
    void aDiscountBeyondWhatIsUnpaidIsRefusedAndRecordsNothing() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "12.51", "12.51").andExpect(status().isCreated());
        UUID line = line(sale);

        fixtures.percentOff(sale, line, null, "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISCOUNT_EXCEEDS_UNPAID"))
                .andExpect(jsonPath("$.unpaid").value(12.49));

        assertThat(adjustments(sale.order())).isZero();
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.due.amount").value(25.00));
    }

    @Test
    void anOpenAttemptCountsAgainstTheHeadroom() throws Exception {
        Sale sale = fixtures.sale("2");
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null);
        fixtures.pay(sale.cashier(), sale.order(), card, "20.00", null, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        fixtures.percentOff(sale, line(sale), null, "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISCOUNT_EXCEEDS_UNPAID"));
    }

    @Test
    void anOfferOfOneUnitSplitsTheLineUnderThePaymentLock() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "10.00", "10.00").andExpect(status().isCreated());
        UUID line = line(sale);

        fixtures.offer(sale, line, "1").andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(12.50))
                .andExpect(jsonPath("$.coverage.length()").value(2))
                .andExpect(jsonPath("$.coverage[1].gross.amount").value(0));

        assertThat(fixtures.count("SELECT count(*) FROM orders.order_line_split WHERE source_line_id = ?", line))
                .isOne();
    }

    @Test
    void aLineWithQuantityInALivePartitionIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = line(sale);
        fixtures.partitionItems(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString(), line, "1").andExpect(status().isCreated());

        fixtures.percentOff(sale, line, "1", "50") // even the unit no partition holds
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUANTITY_PARTITIONED"));
        assertThat(adjustments(sale.order())).isZero();
    }

    @Test
    void aLineOnADocumentNeedsTheFiscalAdapter() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = line(sale);
        UUID partition = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1"));
        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated());

        fixtures.percentOff(sale, line, "1", "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
    }

    @Test
    void aFrozenOrderNeedsTheFiscalAdapter() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString()).andExpect(status().isCreated());

        fixtures.percentOff(sale, line(sale), null, "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CORRECTION_REQUIRES_FISCAL_ADAPTER"));
    }

    @Test
    void aReversalIsRefusedOnTheSettlementRoute() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID line = line(sale);
        fixtures.percentOff(sale, line, null, "50").andExpect(status().isOk());

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        """
                        "lines": [{"lineId": "%s"}], "kind": "REVERSAL", "reason": "back to list\"""".formatted(line),
                        true)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRICE_INCREASE_NOT_ALLOWED"));
    }

    @Test
    void anOfferNeedsTheOfferPermission() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                """
                "lines": [{"lineId": "%s"}], "kind": "OFFER", "reason": "on the house\"""".formatted(line(sale)),
                true, Permission.ORDER_DISCOUNT, Permission.ORDER_VIEW).andExpect(status().isForbidden());
    }

    @Test
    void aDiscountNeedsTheDiscountPermission() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                """
                "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "percent": 10, "reason": "regular\""""
                        .formatted(line(sale)), true, Permission.ORDER_OFFER, Permission.LOYALTY_REDEEM,
                Permission.ORDER_VIEW).andExpect(status().isForbidden());
    }

    @Test
    void aRewardNeedsTheRedeemPermission() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                """
                "lines": [{"lineId": "%s", "quantity": 1}], "kind": "REWARD", "rewardCode": "FREE1",
                "reason": "points\"""".formatted(line(sale)), false, Permission.ORDER_DISCOUNT, Permission.ORDER_OFFER,
                Permission.ORDER_VIEW).andExpect(status().isForbidden());
    }

    @Test
    void aDiscountAboveTheFreeLimitNeedsTheApprover() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                """
                "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "percent": 10, "reason": "regular\""""
                        .formatted(line(sale)), false)
                .andExpect(status().isForbidden());
    }

    @Test
    void anApproverCodeWithoutItsPinIsRefused() throws Exception {
        Sale sale = fixtures.sale("2");

        fixtures.settledDiscountWithHeaders(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), """
                "lines": [{"lineId": "%s"}], "kind": "OFFER", "reason": "on the house\"""".formatted(line(sale)),
                "MGR", null).andExpect(status().isForbidden());
    }

    @Test
    void theOperatorCannotApproveTheirOwnDiscount() throws Exception {
        Sale sale = fixtures.sale("2");
        when(approvers.verify(any(), any(), eq(Permission.ORDER_DISCOUNT_APPROVE), any()))
                .thenReturn(sale.cashier().employeeId());

        fixtures.percentOff(sale, line(sale), null, "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
    }

    @Test
    void theOrdersRouteRefusesUnderThePaymentLockWhileTheSettlementRouteAccepts() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "5.00", "5.00").andExpect(status().isCreated());

        fixtures.ordersRouteDiscount(sale, line(sale), "50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IN_SETTLEMENT"));
        fixtures.percentOff(sale, line(sale), null, "50").andExpect(status().isOk());
    }

    private UUID line(Sale sale) throws Exception {
        return fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
    }

    private long adjustments(UUID order) {
        return fixtures.count("SELECT count(*) FROM orders.order_line_price_adjustment WHERE order_id = ?", order);
    }
}
