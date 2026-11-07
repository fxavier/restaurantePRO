package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.orders.AdjustLine;
import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.SettledAdjustmentCommand;
import com.xavier.backend.orders.exception.OrderRuleException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D105: {@code OrdersApi.adjustLinePrice} is settlement's route under the PAYMENT lock. It runs only inside the
 * caller's transaction, ignores the PAYMENT lock (settlement checked the headroom), refuses INVOICE and a REVERSAL,
 * and keeps the D106 limits and the self-approval rule.
 */
@OrdersModuleTest
class SettledAdjustmentApiTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;

    @BeforeEach
    void aConfirmedLineOfTwoUnderThePaymentLock() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "2", 0, operator, terminal);
        promotions.publish(shop.store());
        inPos(() -> {
            ordersApi.lockForPayment(order);
            return null;
        });
    }

    @Test
    void underThePaymentLockTheSettledRouteDiscountsAndSplits() {
        long version = ordersApi.billingView(order).version();

        OrderBillingView bill = inPos(() -> ordersApi.adjustLinePrice(command(version, AdjustmentKind.DISCOUNT,
                BigDecimal.ONE, new BigDecimal("5")), null));

        assertThat(bill.due().amount()).isEqualByComparingTo("24.38"); // 12.50 + 11.88 (5 % HALF_UP)
        assertThat(bill.lines()).hasSize(2);
        assertThat(bill.version()).isEqualTo(version + 1);
    }

    @Test
    void anOfferNeedsTheApproverAndAReversalIsRefused() {
        long version = ordersApi.billingView(order).version();

        assertThatThrownBy(() -> inPos(() -> ordersApi.adjustLinePrice(command(version, AdjustmentKind.OFFER, null,
                null), null))).isInstanceOf(ApprovalDeniedException.class);
        assertThatThrownBy(() -> inPos(() -> ordersApi.adjustLinePrice(command(version, AdjustmentKind.OFFER, null,
                null), operator))).isInstanceOfSatisfying(OrderRuleException.class,
                e -> assertThat(e.code()).isEqualTo("SELF_APPROVAL"));
        assertThatThrownBy(() -> inPos(() -> ordersApi.adjustLinePrice(command(version, AdjustmentKind.REVERSAL,
                null, null), null))).isInstanceOfSatisfying(OrderRuleException.class,
                e -> assertThat(e.code()).isEqualTo("PRICE_INCREASE_NOT_ALLOWED"));
        UUID approver = UUID.randomUUID();
        assertThat(inPos(() -> ordersApi.adjustLinePrice(command(version, AdjustmentKind.OFFER, null, null),
                approver)).due().amount()).isEqualByComparingTo("0.00");
    }

    @Test
    void itRunsOnlyInTheCallersTransaction() {
        assertThatThrownBy(() -> ordersApi.adjustLinePrice(command(1, AdjustmentKind.DISCOUNT, null,
                BigDecimal.TEN), null)).isInstanceOf(IllegalTransactionStateException.class);
    }

    SettledAdjustmentCommand command(long version, AdjustmentKind kind, BigDecimal quantity, BigDecimal percent) {
        return new SettledAdjustmentCommand(order, version, List.of(new AdjustLine(line, quantity)), kind, percent,
                null, null, "settled", operator);
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
