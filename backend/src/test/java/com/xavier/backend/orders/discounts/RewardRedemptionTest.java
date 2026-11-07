package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersTestClock;
import com.xavier.backend.orders.SettledVoidCommand;
import com.xavier.backend.promotions.loyalty.LoyaltyFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D112 (P46): a loyalty reward lowers the price of one unit of a confirmed line. Orders splits that unit off and
 * redeems through {@code LoyaltyApi} in the same transaction (the points pay, no approver); voiding the rewarded unit
 * gives the points back; the order's customer cannot change once a reward was redeemed. A42 (orders half).
 */
@OrdersModuleTest
class RewardRedemptionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    String reward;
    Shop shop;
    UUID order;
    UUID line;
    UUID member;

    @Autowired
    OrdersTestClock clock;

    /**
     * The loyalty program is company-wide and the loyalty tests publish versions with a clock that runs ahead; this
     * test's clock is pinned just after the latest publication, so it can retire that version and earn under its own.
     */
    @BeforeEach
    void aMemberWithFiftyPointsAtATableOfThreeCoffees() throws Exception {
        Instant latest = jdbc.sql("SELECT max(published_at) FROM promotions.loyalty_program_version")
                .query(Instant.class).optional().orElse(null);
        Instant now = Instant.now();
        clock.pin(latest == null || latest.isBefore(now) ? now : latest.plusSeconds(1));
        reward = "FREE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        loyalty.publishProgram("1.00", 1, LoyaltyFixtures.reward(reward, 10, "FREE_UNIT", null, null, null));
        member = loyalty.member();
        loyalty.earn(member, UUID.randomUUID(), "50.00");
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
    }

    @AfterEach
    void releaseTheClock() {
        clock.release();
    }

    @Test
    void aRewardSplitsOneUnitOffAtItsPriceAndTheVoidGivesThePointsBack() throws Exception {
        customer(1, member).andExpect(status().isOk());

        redeem(2, "1").andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(25.00))
                .andExpect(jsonPath("$.lines[1].unitPrice.amount").value(0.00))
                .andExpect(jsonPath("$.lines[1].origin").value("LOYALTY"))
                .andExpect(jsonPath("$.lines[1].adjustments[0].rewardCode").value(reward));
        assertThat(loyalty.balance(member)).isEqualTo(40);
        UUID rewarded = jdbc.sql("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?")
                .param(line).query(UUID.class).single();

        OrdersActors.runAsPos(shop.store(), operator, terminal, () -> new TransactionTemplate(transactionManager)
                .execute(status -> ordersApi.voidLine(new SettledVoidCommand(order, 3, rewarded, BigDecimal.ONE,
                        "wrong dish", false, operator), UUID.randomUUID())));

        assertThat(loyalty.balance(member)).isEqualTo(50);
    }

    @Test
    void theCustomerCannotChangeOnceARewardWasRedeemed() throws Exception {
        customer(1, member).andExpect(status().isOk());
        redeem(2, "1").andExpect(status().isOk());

        customer(3, loyalty.member())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_REDEEMED"));
        customer(3, member).andExpect(status().isOk()); // naming the same customer again changes nothing
    }

    @Test
    void aRewardNeedsTheOrdersCustomerAndOneUnit() throws Exception {
        redeem(1, "1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_CUSTOMER_REQUIRED"));
        customer(1, member).andExpect(status().isOk());

        redeem(2, "2").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REWARD_NEEDS_SINGLE_UNIT"));
        assertThat(loyalty.balance(member)).isEqualTo(50);
    }

    @Test
    void aMergedRewardedLineGivesThePointsBackOnceWhenItsCopyIsVoided() throws Exception {
        customer(1, member).andExpect(status().isOk());
        redeem(2, "1").andExpect(status().isOk());
        UUID rewarded = rewardedLine();
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);

        fixtures.transfer(shop, order, 3, target, 0, java.util.Map.of(line, "2", rewarded, "1"), true, operator,
                terminal, UUID.randomUUID().toString()).andExpect(status().isCreated()); // M1 merged into M2
        UUID copy = copyOf(target, rewarded);
        assertThat(ordersApi.billingView(target).lines()).filteredOn(billed -> billed.lineId().equals(copy))
                .singleElement().satisfies(billed -> assertThat(billed.unitPrice().amount()).isZero());
        voidOn(target, copy);

        assertThat(loyalty.balance(member)).isEqualTo(50);
        assertThat(redeemReversals(rewarded)).isOne();
    }

    @Test
    void aRewardedLineTransferredAloneGivesThePointsBackWhenItsCopyIsVoided() throws Exception {
        customer(1, member).andExpect(status().isOk());
        redeem(2, "1").andExpect(status().isOk());
        UUID rewarded = rewardedLine();
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        fixtures.transfer(shop, order, 3, target, 0, java.util.Map.of(rewarded, "1"), false, operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        voidOn(target, copyOf(target, rewarded));

        assertThat(loyalty.balance(member)).isEqualTo(50);
        assertThat(redeemReversals(rewarded)).isOne();
    }

    UUID rewardedLine() {
        return jdbc.sql("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?")
                .param(line).query(UUID.class).single();
    }

    UUID copyOf(UUID target, UUID source) {
        return jdbc.sql("SELECT target_line_id FROM orders.order_transfer_line WHERE source_line_id = ? "
                + "AND target_order_id = ?").param(source).param(target).query(UUID.class).single();
    }

    void voidOn(UUID target, UUID copy) {
        long version = ordersApi.billingView(target).version();
        OrdersActors.runAsPos(shop.store(), operator, terminal, () -> new TransactionTemplate(transactionManager)
                .execute(status -> ordersApi.voidLine(new SettledVoidCommand(target, version, copy, BigDecimal.ONE,
                        "wrong dish", false, operator), UUID.randomUUID())));
    }

    long redeemReversals(UUID rewardedLine) {
        return jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry r JOIN promotions.loyalty_point_entry d "
                + "ON r.reverses_entry_id = d.id WHERE d.source_type = 'ORDER_LINE' AND d.source_id = ? "
                + "AND r.kind = 'REDEEM_REVERSAL'").param(rewardedLine).query(Long.class).single();
    }

    @Test
    void anUnknownOrInactiveCustomerIsRefused() throws Exception {
        customer(1, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    ResultActions redeem(long orderVersion, String quantity) throws Exception {
        return fixtures.discount(shop, order, """
                        {"orderVersion": %d, "lines": [{"lineId": "%s", "quantity": %s}], "kind": "REWARD",
                         "rewardCode": "%s", "reason": "loyalty"}""".formatted(orderVersion, line, quantity, reward),
                operator, terminal, Permission.LOYALTY_REDEEM, null);
    }

    ResultActions customer(long orderVersion, UUID customer) throws Exception {
        return mvc.perform(post(OrdersFixtures.POS + "/orders/" + order + "/customer")
                .with(OrdersActors.pos(shop.store(), operator, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderVersion\": %d, \"customerId\": \"%s\"}".formatted(orderVersion, customer)));
    }
}
