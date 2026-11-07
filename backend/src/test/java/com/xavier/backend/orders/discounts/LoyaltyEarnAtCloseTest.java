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
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.SettledVoidCommand;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
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
 * excluded, out-of-scope families excluded. An order without a member customer earns nothing. A42 (orders half).
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

    @Autowired
    OrdersCatalog ordersCatalog;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    CatalogApi catalogApi;

    @Autowired
    LoyaltyApi loyaltyApi;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();

    /** The loyalty program is company-wide; see RewardRedemptionTest for why the clock is pinned. */
    @BeforeEach
    void pinTheClockAfterTheLatestPublication() {
        Instant latest = jdbc.sql("SELECT max(published_at) FROM promotions.loyalty_program_version")
                .query(Instant.class).optional().orElse(null);
        Instant now = Instant.now();
        clock.pin(latest == null || latest.isBefore(now) ? now : latest.plusSeconds(1));
    }

    @AfterEach
    void releaseTheClock() {
        clock.release();
    }

    /**
     * A program of one point per metical scoped to the coffee's family, and one order with: 3 coffees (one rewarded
     * 2.00 off, 10.50, which never earns), 2 coffees discounted to 11.50, a coffee voided whole, and a juice of
     * another family (out of scope). Eligible = 2 × 12.50 + 2 × 11.50 = 48.00 → 48 points.
     */
    @Test
    void aMemberEarnsOnTheEffectiveGrossOfTheUnrewardedLinesInScopeWhenTheOrderCloses() throws Exception {
        Shop base = fixtures.shop();
        UUID coffees = catalogApi.items(List.of(base.item())).getFirst().familyId();
        UUID juice = OrdersFixtures.id(ordersCatalog.item(base, null, "5.00"));
        Shop shop = new Shop(base.store(), base.item(), base.area(), base.table(), base.priceList(), base.price(),
                fixtures.republish(base.store()));
        String reward = "OFF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        tools.jackson.databind.JsonNode draft = OrdersFixtures.json(loyalty.createDraft("1.00", 1, "",
                "\"" + coffees + "\"").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID program = UUID.fromString(draft.get("id").asString());
        loyalty.addReward(program, LoyaltyFixtures.reward(reward, 10, "AMOUNT_OFF_UNIT", "2.00", null, null))
                .andExpect(status().isCreated());
        loyalty.publish(program, draft.get("version").asLong()).andExpect(status().isOk());
        UUID member = loyalty.member();
        inPos(shop, () -> loyaltyApi.earnForOrder(new EarnCommand(UUID.randomUUID(), shop.store(), member,
                LocalDate.now(), clock.instant(), List.of(new EarnLine(shop.item(), coffees,
                Money.of("10.00", Currency.getInstance("MZN")), false)))));
        assertThat(loyalty.balance(member)).isEqualTo(10);

        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        UUID rewarded = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        UUID discounted = fixtures.confirmedLine(shop, order, "2", 1, operator, terminal);
        UUID voided = fixtures.confirmedLine(shop, order, "1", 2, operator, terminal);
        UUID juiceLine = id(OrdersFixtures.json(fixtures.addLine(shop, order, """
                {"itemId": "%s", "quantity": 1}""".formatted(juice), operator, terminal, UUID.randomUUID().toString())
                .andReturn().getResponse().getContentAsString()));
        fixtures.confirm(shop, order, 3, shop.catalogVersion(), List.of(juiceLine), operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        customer(shop, order, 4, member);
        promotions.publish(shop.store()); // the default limits: 10 % free (D106)
        fixtures.discount(shop, order, """
                        {"orderVersion": 5, "lines": [{"lineId": "%s", "quantity": 1}], "kind": "REWARD",
                         "rewardCode": "%s", "reason": "loyalty"}""".formatted(rewarded, reward), operator, terminal,
                        Permission.LOYALTY_REDEEM, null)
                .andExpect(status().isOk());
        fixtures.discount(shop, order, """
                        {"orderVersion": 6, "lines": [{"lineId": "%s"}], "kind": "DISCOUNT", "unitPrice": 11.50,
                         "reason": "regular"}""".formatted(discounted), operator, terminal,
                        Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        inPos(shop, () -> ordersApi.voidLine(new SettledVoidCommand(order, 7, voided, BigDecimal.ONE, "wrong dish",
                false, operator), UUID.randomUUID()));
        assertThat(loyalty.balance(member)).isZero();

        close(shop, order, 8);

        assertThat(loyalty.balance(member)).isEqualTo(48);
        assertThat(jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE source_type = 'ORDER' "
                + "AND source_id = ? AND kind = 'EARN'").param(order).query(Long.class).single()).isOne();
    }

    /**
     * D111, D112a: a rewarded unit (12.50 − 2.00 = 10.50) is transferred in part to a second table of the same member;
     * neither the copy nor the original's remainder earns. Table 1 earns on its 2 other coffees (25.00), table 2 on
     * its own coffee (12.50).
     */
    @Test
    void aTransferredCopyAndTheRemainderOfARewardedUnitEarnNothing() throws Exception {
        String reward = "OFF" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        loyalty.publishProgram("1.00", 1, LoyaltyFixtures.reward(reward, 10, "AMOUNT_OFF_UNIT", "2.00", null, null));
        UUID member = loyalty.member();
        Shop shop = fixtures.shop();
        inPos(shop, () -> loyaltyApi.earnForOrder(new EarnCommand(UUID.randomUUID(), shop.store(), member,
                LocalDate.now(), clock.instant(), List.of(new EarnLine(shop.item(), null,
                Money.of("10.00", Currency.getInstance("MZN")), false)))));
        UUID first = id(fixtures.openedOrder(shop, operator, terminal));
        UUID line = fixtures.confirmedLine(shop, first, "3", 0, operator, terminal);
        customer(shop, first, 1, member);
        fixtures.discount(shop, first, """
                        {"orderVersion": 2, "lines": [{"lineId": "%s", "quantity": 1}], "kind": "REWARD",
                         "rewardCode": "%s", "reason": "loyalty"}""".formatted(line, reward), operator, terminal,
                        Permission.LOYALTY_REDEEM, null)
                .andExpect(status().isOk());
        UUID rewarded = jdbc.sql("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?")
                .param(line).query(UUID.class).single();
        UUID second = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        fixtures.confirmedLine(shop, second, "1", 0, operator, terminal);
        customer(shop, second, 1, member);
        fixtures.transfer(shop, first, 3, second, 2, java.util.Map.of(rewarded, "0.5"), false, operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        close(shop, second, 3);
        assertThat(loyalty.balance(member)).isEqualTo(12);
        close(shop, first, 4);

        assertThat(loyalty.balance(member)).isEqualTo(37);
    }

    @Test
    void anOrderWithoutAMemberEarnsNothing() throws Exception {
        loyalty.publishProgram("1.00", 1);
        Shop shop = fixtures.shop();
        UUID order = id(fixtures.openedOrder(shop, operator, terminal));
        fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        customer(shop, order, 1, loyalty.customer());

        close(shop, order, 2);

        assertThat(jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE source_id = ?")
                .param(order).query(Long.class).single()).isZero();
    }

    <T> T inPos(Shop shop, java.util.function.Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }

    void close(Shop shop, UUID order, long version) {
        inPos(shop, () -> {
            ordersApi.close(order, version, operator);
            return null;
        });
    }

    void customer(Shop shop, UUID order, long orderVersion, UUID customer) throws Exception {
        mvc.perform(post(OrdersFixtures.POS + "/orders/" + order + "/customer")
                        .with(OrdersActors.pos(shop.store(), operator, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderVersion\": %d, \"customerId\": \"%s\"}".formatted(orderVersion, customer)))
                .andExpect(status().isOk());
    }
}
