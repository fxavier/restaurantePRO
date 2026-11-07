package com.xavier.backend.settlement;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D112, D112a through settlement's routes: under the PAYMENT lock a member redeems a reward on one unit (the
 * settlement discount route, no approver: the points pay for it), and the settled void of that unit gives the points
 * back, because orders reverses the redemption inside {@code voidLine}.
 */
@SettlementModuleTest
class SettledRewardVoidTest {

    static final String PROGRAMS = "/api/v1/backoffice/loyalty/program-versions";

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    LoyaltyApi loyalty;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void realClock() {
        clock.reset();
    }

    @Test
    void aRewardRedeemedUnderThePaymentLockIsGivenBackByTheSettledVoidOfItsUnit() throws Exception {
        when(approvers.verify(any(), any(), eq(Permission.ORDER_VOID_APPROVE), any())).thenReturn(UUID.randomUUID());
        afterTheLatestLoyaltyPublication();
        String reward = "FREE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        publishProgram(reward);
        Sale sale = fixtures.sale("2"); // 2 × 12.50
        UUID member = member(sale);
        new TransactionTemplate(transactionManager).execute(tx -> loyalty.earnForOrder(new EarnCommand(
                UUID.randomUUID(), sale.store(), member, java.time.LocalDate.now(clock), clock.instant(),
                List.of(new EarnLine(UUID.randomUUID(), UUID.randomUUID(), CustomersFixtures.mzn("50.00"), false)))));
        assertThat(loyalty.balance(member).points()).isEqualTo(50);
        setCustomer(sale, member);
        fixtures.payCash(sale, "5.00", "5.00").andExpect(status().isCreated());
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();

        fixtures.settledDiscount(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                        """
                        "lines": [{"lineId": "%s", "quantity": 1}], "kind": "REWARD", "rewardCode": "%s",
                        "reason": "points\"""".formatted(line, reward), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due.amount").value(12.50));
        assertThat(loyalty.balance(member).points()).isEqualTo(40);
        UUID rewarded = jdbc.sql("SELECT target_line_id FROM orders.order_line_split WHERE source_line_id = ?")
                .param(line).query(UUID.class).single();

        fixtures.settledVoid(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                rewarded, "1", UUID.randomUUID().toString()).andExpect(status().isCreated());

        assertThat(loyalty.balance(member).points()).isEqualTo(50);
    }

    /** The program is company-wide; other contexts publish with clocks ahead, so this one moves just after them. */
    void afterTheLatestLoyaltyPublication() {
        Instant latest = jdbc.sql("SELECT max(published_at) FROM promotions.loyalty_program_version")
                .query(Instant.class).optional().orElse(null);
        if (latest != null && latest.isAfter(clock.instant())) {
            clock.moveTo(latest.plusSeconds(1));
        }
    }

    void publishProgram(String reward) throws Exception {
        String draft = mvc.perform(idem(post(PROGRAMS)).with(account(null, Permission.LOYALTY_PROGRAM_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"spendUnit": 1.00, "pointsPerUnit": 1, "itemIds": [], "familyIds": []}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID version = CustomersFixtures.id(draft);
        mvc.perform(idem(post(PROGRAMS + "/" + version + "/rewards"))
                        .with(account(null, Permission.LOYALTY_PROGRAM_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Reward %s", "pointsCost": 10, "effect": "FREE_UNIT",
                                 "amount": null, "itemId": null, "familyId": null}""".formatted(reward, reward)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(PROGRAMS + "/" + version + "/publish"))
                        .with(account(null, Permission.LOYALTY_PROGRAM_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}"))
                .andExpect(status().isOk());
    }

    UUID member(Sale sale) throws Exception {
        CustomersFixtures.Pos pos = CustomersFixtures.pos(sale.store());
        UUID customer = customers.newCustomer(pos, "Member " + CustomersFixtures.token(), null, null);
        mvc.perform(idem(post(CustomersFixtures.POS + "/" + customer + "/loyalty"))
                .with(pos.with(Permission.LOYALTY_ENROLL))).andExpect(status().isCreated());
        return customer;
    }

    void setCustomer(Sale sale, UUID customer) throws Exception {
        mvc.perform(post(SettlementFixtures.ORDERS + "/" + sale.order() + "/customer")
                        .with(OrdersActors.pos(sale.store(), sale.cashier().employeeId(), sale.cashier().terminalId(),
                                Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderVersion\": %d, \"customerId\": \"%s\"}".formatted(
                                fixtures.orderVersion(sale.cashier(), sale.order()), customer)))
                .andExpect(status().isOk());
    }
}
