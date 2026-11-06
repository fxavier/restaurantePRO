package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.ACCOUNTS;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.mzn;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.redeemCommand;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.RedeemCommand;
import com.xavier.backend.promotions.RedemptionResult;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
import com.xavier.backend.shared.exception.CodedException;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D112, P46: a redemption is a reward on one unit, never a payment: a free unit (price 0) or an amount off it (never
 * below 0), for a member with an ACTIVE account and enough points, on a line in the reward's scope. One REDEEM per
 * line; a repeat returns it, a repeat with other values is a 409. Voiding the line reverses it once
 * (REDEEM_REVERSAL), and the points come back.
 */
@LoyaltyModuleTest
class LoyaltyRedemptionTest {

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    LoyaltyApi api;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    UUID coffee;
    UUID drinks;
    UUID member;

    @BeforeEach
    void aProgramWithRewardsAndAMemberWithOneHundredPoints() throws Exception {
        coffee = UUID.randomUUID();
        drinks = UUID.randomUUID();
        loyalty.publishProgram("1.00", 1,
                reward("FREE_COFFEE", 30, "FREE_UNIT", "null", coffee, null),
                reward("DRINK_OFF", 20, "AMOUNT_OFF_UNIT", "25.00", null, drinks),
                reward("ANY_FREE", 90, "FREE_UNIT", "null", null, null));
        member = loyalty.member();
        loyalty.earn(member, UUID.randomUUID(), "100.00");
    }

    @Test
    void aFreeUnitRewardMakesTheUnitFreeAndSpendsItsCost() {
        RedemptionResult result = redeem(redeemCommand(member, "FREE_COFFEE", UUID.randomUUID(), coffee, null,
                "45.00"));

        assertThat(result.points()).isEqualTo(30);
        assertThat(result.newUnitPrice()).isEqualTo(mzn("0.00"));
        assertThat(loyalty.balance(member)).isEqualTo(70);
    }

    @Test
    void anAmountOffNeverTakesTheUnitBelowZero() {
        assertThat(redeem(redeemCommand(member, "DRINK_OFF", UUID.randomUUID(), UUID.randomUUID(), drinks,
                "40.00")).newUnitPrice()).isEqualTo(mzn("15.00"));
        assertThat(redeem(redeemCommand(member, "DRINK_OFF", UUID.randomUUID(), UUID.randomUUID(), drinks,
                "10.00")).newUnitPrice()).isEqualTo(mzn("0.00"));
    }

    @Test
    void aRewardOutsideItsScopeOrUnknownIsNotApplicable() {
        assertRule(() -> redeem(redeemCommand(member, "FREE_COFFEE", UUID.randomUUID(), UUID.randomUUID(), null,
                "45.00")), "LOYALTY_REWARD_NOT_APPLICABLE");
        assertRule(() -> redeem(redeemCommand(member, "DRINK_OFF", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "45.00")), "LOYALTY_REWARD_NOT_APPLICABLE");
        assertRule(() -> redeem(redeemCommand(member, "NO_SUCH", UUID.randomUUID(), coffee, null, "45.00")),
                "LOYALTY_REWARD_NOT_APPLICABLE");
        assertRule(() -> redeem(redeemCommand(member, "ANY_FREE", UUID.randomUUID(), coffee, null, "0.00")),
                "LOYALTY_REWARD_NOT_APPLICABLE");
        assertThat(loyalty.balance(member)).isEqualTo(100);
    }

    @Test
    void onlyAnActiveMemberWithEnoughPointsRedeems() throws Exception {
        UUID notEnrolled = loyalty.customer();
        assertRule(() -> redeem(redeemCommand(notEnrolled, "ANY_FREE", UUID.randomUUID(), coffee, null, "5.00")),
                "LOYALTY_NOT_MEMBER");

        redeem(redeemCommand(member, "ANY_FREE", UUID.randomUUID(), coffee, null, "5.00"));
        assertThatThrownBy(() -> redeem(redeemCommand(member, "ANY_FREE", UUID.randomUUID(), coffee, null, "5.00")))
                .isInstanceOfSatisfying(LoyaltyRuleException.class, e -> {
                    assertThat(e.code()).isEqualTo("LOYALTY_INSUFFICIENT_POINTS");
                    assertThat(e.properties()).containsEntry("balance", 10L).containsEntry("required", 90);
                });

        mvc.perform(idem(post(ACCOUNTS + "/" + member + "/suspend")).with(account(null, Permission.LOYALTY_ENROLL))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "card shared"}"""))
                .andExpect(status().isOk());
        assertRule(() -> redeem(redeemCommand(member, "DRINK_OFF", UUID.randomUUID(), UUID.randomUUID(), drinks,
                "5.00")), "LOYALTY_ACCOUNT_SUSPENDED");
    }

    @Test
    void aLineIsRedeemedOnceAndARepeatWithOtherValuesIsAConflict() {
        UUID line = UUID.randomUUID();
        RedeemCommand command = redeemCommand(member, "FREE_COFFEE", line, coffee, null, "45.00");
        RedemptionResult first = redeem(command);

        assertThat(redeem(command)).isEqualTo(first);
        assertThatThrownBy(() -> redeem(new RedeemCommand(member, "FREE_COFFEE", line, command.orderId(),
                command.storeId(), command.businessDate(), coffee, null, mzn("50.00"))))
                .isInstanceOfSatisfying(LoyaltyConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("LOYALTY_SOURCE_MISMATCH"));
        assertThatThrownBy(() -> redeem(new RedeemCommand(member, "ANY_FREE", line, command.orderId(),
                command.storeId(), command.businessDate(), coffee, null, mzn("45.00"))))
                .isInstanceOf(LoyaltyConflictException.class);
        assertThat(loyalty.balance(member)).isEqualTo(70);
    }

    @Test
    void voidingTheRewardedLineReversesTheRedemptionOnce() {
        UUID line = UUID.randomUUID();
        RedemptionResult redeemed = redeem(redeemCommand(member, "FREE_COFFEE", line, coffee, null, "45.00"));
        UUID voidId = UUID.randomUUID();

        tx.executeWithoutResult(status -> api.reverseRedemption(line, voidId));
        tx.executeWithoutResult(status -> api.reverseRedemption(line, voidId));

        assertThat(loyalty.balance(member)).isEqualTo(100);
        assertThat(jdbc.sql("""
                        SELECT points || ':' || reverses_entry_id || ':' || void_id FROM promotions.loyalty_point_entry
                        WHERE customer_id = ? AND kind = 'REDEEM_REVERSAL'""")
                .param(member).query(String.class).list())
                .containsExactly("30:" + redeemed.entryId() + ":" + voidId);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> api.reverseRedemption(line, UUID.randomUUID())))
                .isInstanceOfSatisfying(LoyaltyConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("LOYALTY_SOURCE_MISMATCH"));
    }

    @Test
    void returnedPointsCanBeSpentAgain() {
        UUID line = UUID.randomUUID();
        redeem(redeemCommand(member, "ANY_FREE", line, coffee, null, "45.00"));
        tx.executeWithoutResult(status -> api.reverseRedemption(line, UUID.randomUUID()));

        RedemptionResult again = redeem(redeemCommand(member, "ANY_FREE", UUID.randomUUID(), coffee, null, "45.00"));

        assertThat(again.points()).isEqualTo(90);
        assertThat(loyalty.balance(member)).isEqualTo(10);
    }

    @Test
    void voidingALineWithoutARedemptionChangesNothing() {
        tx.executeWithoutResult(status -> api.reverseRedemption(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(loyalty.balance(member)).isEqualTo(100);
    }

    @Test
    void redeemingAndReversingJoinTheCallersTransactionOnly() {
        assertThatThrownBy(() -> api.redeem(redeemCommand(member, "ANY_FREE", UUID.randomUUID(), coffee, null,
                "5.00"))).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> api.reverseRedemption(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private RedemptionResult redeem(RedeemCommand command) {
        return tx.execute(status -> api.redeem(command));
    }

    private static void assertRule(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LoyaltyRuleException.class,
                e -> assertThat(((CodedException) e).code()).isEqualTo(code));
    }
}
