package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.ACCOUNTS;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersFixtures.Pos;
import com.xavier.backend.organization.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Members (D110): explicit enrollment at the POS ({@code LOYALTY_ENROLL}), suspension and resumption with a reason,
 * the balance (Σ, {@code LOYALTY_VIEW}) and the statement with a running balance, and manual adjustments with a
 * reason ({@code LOYALTY_ADJUST}, §8.4) that never take the balance below zero. Every write is audited.
 */
@LoyaltyModuleTest
class LoyaltyAccountHttpTest {

    static final String POS = CustomersFixtures.POS;

    @Autowired
    MockMvc mvc;

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    Pos cashier;
    UUID customer;

    @BeforeEach
    void aCustomerAtAPos() throws Exception {
        cashier = loyalty.cashier();
        customer = loyalty.customer();
    }

    @Test
    void enrollmentIsExplicitAndOnce() throws Exception {
        mvc.perform(get(POS + "/" + customer + "/loyalty").with(cashier.with(Permission.LOYALTY_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrolled").value(false))
                .andExpect(jsonPath("$.points").value(0));

        loyalty.enroll(cashier, customer).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customer.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.points").value(0));
        loyalty.enroll(cashier, customer).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_ALREADY_ENROLLED"));

        assertThat(jdbc.sql("""
                        SELECT enrolled_by_actor_kind || ':' || enrolled_by_actor_id FROM promotions.loyalty_account
                        WHERE customer_id = ?""").param(customer).query(String.class).single())
                .isEqualTo("EMPLOYEE:" + cashier.employeeId());
        assertThat(audits("LOYALTY_ENROLLED")).isEqualTo(1);
    }

    @Test
    void anInactiveOrUnknownCustomerIsNotEnrolled() throws Exception {
        jdbc.sql("UPDATE customers.customer SET active = false, version = version + 1 WHERE id = ?")
                .param(customer).update();

        loyalty.enroll(cashier, customer).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CUSTOMER_INACTIVE"));
        loyalty.enroll(cashier, UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void enrollingNeedsTheEnrollPermissionAtThePos() throws Exception {
        mvc.perform(idem(post(POS + "/" + customer + "/loyalty")).with(cashier.with(Permission.LOYALTY_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(POS + "/" + customer + "/loyalty")).with(account(null, Permission.LOYALTY_ENROLL)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAccountIsSuspendedAndResumedWithAReason() throws Exception {
        loyalty.enroll(cashier, customer).andExpect(status().isCreated());

        transition("suspend", " ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        transition("suspend", "card shared").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspendedReason").value("card shared"));
        transition("suspend", "again").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOYALTY_ACCOUNT_STATE"));
        transition("resume", "sorted out").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        transition("resume", "again").andExpect(status().isConflict());

        transition(UUID.randomUUID(), "suspend", "unknown").andExpect(status().isNotFound());
        UUID notEnrolled = loyalty.customer();
        transition(notEnrolled, "suspend", "not a member").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_NOT_MEMBER"));
        assertThat(audits("LOYALTY_SUSPENDED")).isEqualTo(1);
        assertThat(audits("LOYALTY_RESUMED")).isEqualTo(1);
    }

    @Test
    void adjustmentsNeedAReasonAndNeverOverdraw() throws Exception {
        loyalty.enroll(cashier, customer).andExpect(status().isCreated());

        adjust(25, "welcome bonus").andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.points").value(25));
        adjust(-30, "correction").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOYALTY_INSUFFICIENT_POINTS"))
                .andExpect(jsonPath("$.balance").value(25))
                .andExpect(jsonPath("$.required").value(30));
        adjust(5, " ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        adjust(0, "nothing").andExpect(status().isBadRequest());
        adjust(-10, "correction").andExpect(status().isCreated());

        balance().andExpect(jsonPath("$.points").value(15));
        mvc.perform(idem(post(ACCOUNTS + "/" + customer + "/adjustments"))
                        .with(account(null, Permission.LOYALTY_VIEW)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points": 5, "reason": "no permission", "storeId": "%s"}""".formatted(cashier.storeId())))
                .andExpect(status().isForbidden());
        assertThat(audits("LOYALTY_ADJUSTED")).isEqualTo(2);
    }

    @Test
    void aReplayedAdjustmentIsRecordedOnce() throws Exception {
        loyalty.enroll(cashier, customer).andExpect(status().isCreated());
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(idem(post(ACCOUNTS + "/" + customer + "/adjustments"), key)
                            .with(account(null, Permission.LOYALTY_ADJUST)).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"points": 7, "reason": "goodwill", "storeId": "%s"}""".formatted(cashier.storeId())))
                    .andExpect(status().isCreated());
        }

        balance().andExpect(jsonPath("$.points").value(7));
    }

    @Test
    void theStatementShowsEachEntryWithItsRunningBalance() throws Exception {
        loyalty.publishProgram("1.00", 1, reward("R10", 10, "FREE_UNIT", "null", null, null));
        loyalty.enroll(cashier, customer).andExpect(status().isCreated());
        Instant start = clock.instant();
        loyalty.earn(customer, UUID.randomUUID(), "40.00");
        clock.advance(Duration.ofSeconds(1));
        loyalty.redeem(customer, "R10", "6.00");
        clock.advance(Duration.ofSeconds(1));
        adjust(-5, "correction").andExpect(status().isCreated());
        clock.advance(Duration.ofSeconds(1));

        mvc.perform(get(ACCOUNTS + "/" + customer + "/statement").param("from", start.toString())
                        .param("to", clock.instant().toString()).with(account(null, Permission.LOYALTY_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(0))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].kind").value("EARN"))
                .andExpect(jsonPath("$.lines[0].balance").value(40))
                .andExpect(jsonPath("$.lines[1].kind").value("REDEEM"))
                .andExpect(jsonPath("$.lines[1].points").value(-10))
                .andExpect(jsonPath("$.lines[1].balance").value(30))
                .andExpect(jsonPath("$.lines[2].kind").value("ADJUSTMENT"))
                .andExpect(jsonPath("$.lines[2].reason").value("correction"))
                .andExpect(jsonPath("$.lines[2].balance").value(25))
                .andExpect(jsonPath("$.closingBalance").value(25));
        mvc.perform(get(ACCOUNTS + "/" + customer + "/statement").param("from", clock.instant().toString())
                        .with(account(null, Permission.LOYALTY_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingBalance").value(25))
                .andExpect(jsonPath("$.lines").isEmpty());
        balance().andExpect(jsonPath("$.points").value(25));
    }

    @Test
    void theStatementNeedsTheViewPermissionAndAValidPeriod() throws Exception {
        mvc.perform(get(ACCOUNTS + "/" + customer + "/statement").with(account(null, Permission.LOYALTY_ADJUST)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ACCOUNTS + "/" + customer + "/statement").param("from", "2026-11-05T00:00:00Z")
                        .param("to", "2026-11-04T00:00:00Z").with(account(null, Permission.LOYALTY_VIEW)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        mvc.perform(get(ACCOUNTS + "/" + UUID.randomUUID() + "/statement")
                        .with(account(null, Permission.LOYALTY_VIEW)))
                .andExpect(status().isNotFound());
    }

    /** D42: the permission is checked before anything is read, so an unknown customer is not revealed (403). */
    @Test
    void theAuthorizationComesBeforeTheCustomerIsLookedUp() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get(ACCOUNTS + "/" + unknown + "/statement").with(account(null, Permission.LOYALTY_ADJUST)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(ACCOUNTS + "/" + unknown + "/adjustments")).with(account(null, Permission.LOYALTY_VIEW))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"points": 5, "reason": "why", "storeId": "%s"}""".formatted(cashier.storeId())))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(POS + "/" + unknown + "/loyalty")).with(cashier.with(Permission.LOYALTY_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS + "/" + unknown + "/loyalty").with(cashier.with(Permission.LOYALTY_ENROLL)))
                .andExpect(status().isForbidden());
    }

    /** D42 store first: an adjustment is made in a store's name; the grant must cover that store. */
    @Test
    void anAdjustmentIsAuthorizedInItsStoreAndRecordsIt() throws Exception {
        loyalty.enroll(cashier, customer).andExpect(status().isCreated());
        UUID store = cashier.storeId();
        UUID other = loyalty.cashier().storeId();

        adjustAs(account(store, Permission.LOYALTY_ADJUST), other, 5, "goodwill").andExpect(status().isForbidden());
        adjustAs(account(store, Permission.LOYALTY_ADJUST), null, 5, "goodwill").andExpect(status().isBadRequest());
        String entry = adjustAs(account(store, Permission.LOYALTY_ADJUST), store, 5, "goodwill")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andReturn().getResponse().getContentAsString();

        assertThat(jdbc.sql("SELECT store_id FROM promotions.loyalty_point_entry WHERE id = ?")
                .param(CustomersFixtures.id(entry)).query(UUID.class).single()).isEqualTo(store);
        assertThat(jdbc.sql("""
                        SELECT store_id FROM audit.audit_event WHERE action = 'LOYALTY_ADJUSTED' AND entity_id = ?""")
                .param(customer.toString()).query(UUID.class).list()).containsExactly(store);
    }

    private ResultActions balance() throws Exception {
        return mvc.perform(get(POS + "/" + customer + "/loyalty").with(cashier.with(Permission.LOYALTY_VIEW)))
                .andExpect(status().isOk());
    }

    private ResultActions transition(String action, String reason) throws Exception {
        return transition(customer, action, reason);
    }

    private ResultActions transition(UUID who, String action, String reason) throws Exception {
        return mvc.perform(idem(post(ACCOUNTS + "/" + who + "/" + action))
                .with(account(null, Permission.LOYALTY_ENROLL)).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reason": "%s"}""".formatted(reason)));
    }

    private ResultActions adjust(int points, String reason) throws Exception {
        return adjustAs(account(null, Permission.LOYALTY_ADJUST), cashier.storeId(), points, reason);
    }

    private ResultActions adjustAs(org.springframework.test.web.servlet.request.RequestPostProcessor who, UUID store,
                                   int points, String reason) throws Exception {
        return mvc.perform(idem(post(ACCOUNTS + "/" + customer + "/adjustments"))
                .with(who).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"points": %d, "reason": "%s", "storeId": %s}""".formatted(points, reason,
                        store == null ? "null" : "\"" + store + "\"")));
    }

    private long audits(String action) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = ? AND entity_id = ?")
                .params(action, customer.toString()).query(Long.class).single();
    }
}
