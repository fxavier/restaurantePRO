package com.xavier.backend.settlement;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.service.OrderSettlementSteps;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D111 through settlement's route (PRD S46): a member's order is paid, issued and closed by {@code POST /close}, and
 * the close's transaction (tx-C, D55) earns its points. A close whose last step failed after committing is resumed by
 * the retry (D63) and still earns once: one EARN entry, the balance unchanged.
 */
@SettlementModuleTest
class LoyaltyEarnAtSettlementCloseTest {

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
    OrderSettlementSteps steps;

    @Autowired
    SettlementTestClock clock;

    @Autowired
    JdbcClient jdbc;

    @AfterEach
    void realStepsAndClock() {
        Mockito.reset(steps);
        clock.reset();
    }

    @Test
    void aMembersOrderClosedThroughSettlementEarnsOnceEvenWhenTheCloseIsResumed() throws Exception {
        afterTheLatestLoyaltyPublication();
        publishProgram("1.00", 1);
        Sale sale = fixtures.sale("2"); // 2 × 12.50
        UUID member = member(sale);
        mvc.perform(post(SettlementFixtures.ORDERS + "/" + sale.order() + "/customer")
                        .with(OrdersActors.pos(sale.store(), sale.cashier().employeeId(), sale.cashier().terminalId(),
                                Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderVersion\": 1, \"customerId\": \"%s\"}".formatted(member)))
                .andExpect(status().isOk());
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());
        String key = UUID.randomUUID().toString();
        Mockito.doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("injected failure after s3");
        }).when(steps).close(any(), any());

        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isInternalServerError());
        assertThat(earnEntries(sale.order())).as("the close committed with its earning").isOne();
        Mockito.reset(steps);
        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"));

        assertThat(earnEntries(sale.order())).isOne();
        assertThat(loyalty.balance(member).points()).isEqualTo(25);
        fixtures.close(sale.cashier(), sale.order(), version, key).andExpect(status().isOk());
        assertThat(earnEntries(sale.order())).isOne();
        assertThat(loyalty.balance(member).points()).isEqualTo(25);
    }

    /**
     * The program is company-wide and other test contexts publish versions with clocks that run ahead; this one
     * moves its clock just after the latest publication so it can retire it and earn under its own version.
     */
    void afterTheLatestLoyaltyPublication() {
        Instant latest = jdbc.sql("SELECT max(published_at) FROM promotions.loyalty_program_version")
                .query(Instant.class).optional().orElse(null);
        if (latest != null && latest.isAfter(clock.instant())) {
            clock.moveTo(latest.plusSeconds(1));
        }
    }

    void publishProgram(String spendUnit, int pointsPerUnit) throws Exception {
        String draft = mvc.perform(idem(post(PROGRAMS)).with(account(null, Permission.LOYALTY_PROGRAM_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"spendUnit": %s, "pointsPerUnit": %d, "itemIds": [], "familyIds": []}"""
                                .formatted(spendUnit, pointsPerUnit)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(idem(post(PROGRAMS + "/" + CustomersFixtures.id(draft) + "/publish"))
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

    long earnEntries(UUID order) {
        return fixtures.count("SELECT count(*) FROM promotions.loyalty_point_entry WHERE source_type = 'ORDER' "
                + "AND source_id = ? AND kind = 'EARN'", order);
    }
}
