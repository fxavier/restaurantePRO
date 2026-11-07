package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersTestClock;
import com.xavier.backend.promotions.loyalty.LoyaltyFixtures;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D111 (P45): the order's customer earns when the order closes, in the close's transaction ({@code OrdersApi.close},
 * which settlement calls in tx-C): ⌊eligible gross ÷ spend unit⌋ × points per unit, after discounts, rewarded lines
 * excluded. An order without a customer, or whose customer is not a member, earns nothing. A42 (orders half).
 */
@OrdersModuleTest
class LoyaltyEarnAtCloseTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersTestClock clock;

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

    /** The loyalty program is company-wide; see RewardRedemptionTest for why the clock is pinned. */
    @BeforeEach
    void aProgramOfOnePointPerMeticalAndATableOfThreeCoffees() throws Exception {
        Instant latest = jdbc.sql("SELECT max(published_at) FROM promotions.loyalty_program_version")
                .query(Instant.class).optional().orElse(null);
        Instant now = Instant.now();
        clock.pin(latest == null || latest.isBefore(now) ? now : latest.plusSeconds(1));
        reward = "FREE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        loyalty.publishProgram("1.00", 1, LoyaltyFixtures.reward(reward, 10, "FREE_UNIT", null, null, null));
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
    }

    @AfterEach
    void releaseTheClock() {
        clock.release();
    }

    @Test
    void aMemberEarnsOnTheEffectiveGrossOfTheUnrewardedLinesWhenTheOrderCloses() throws Exception {
        UUID member = loyalty.member();
        loyalty.earn(member, UUID.randomUUID(), "10.00");
        customer(1, member);
        fixtures.discount(shop, order, """
                        {"orderVersion": 2, "lines": [{"lineId": "%s", "quantity": 1}], "kind": "REWARD",
                         "rewardCode": "%s", "reason": "loyalty"}""".formatted(line, reward), operator, terminal,
                        Permission.LOYALTY_REDEEM, null)
                .andExpect(status().isOk());
        assertThat(loyalty.balance(member)).isZero();

        close(3);

        assertThat(loyalty.balance(member)).isEqualTo(25); // ⌊2 × 12.50 ÷ 1.00⌋, the free coffee excluded
        assertThat(jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE source_type = 'ORDER' "
                + "AND source_id = ? AND kind = 'EARN'").param(order).query(Long.class).single()).isOne();
    }

    @Test
    void anOrderWithoutAMemberEarnsNothing() throws Exception {
        UUID customer = loyalty.customer();
        customer(1, customer);

        close(2);

        assertThat(jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE source_id = ?")
                .param(order).query(Long.class).single()).isZero();
    }

    void close(long version) {
        OrdersActors.runAsPos(shop.store(), operator, terminal, () -> new TransactionTemplate(transactionManager)
                .execute(status -> {
                    ordersApi.close(order, version, operator);
                    return null;
                }));
    }

    void customer(long orderVersion, UUID customer) throws Exception {
        mvc.perform(post(OrdersFixtures.POS + "/orders/" + order + "/customer")
                        .with(OrdersActors.pos(shop.store(), operator, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderVersion\": %d, \"customerId\": \"%s\"}".formatted(orderVersion, customer)))
                .andExpect(status().isOk());
    }
}
