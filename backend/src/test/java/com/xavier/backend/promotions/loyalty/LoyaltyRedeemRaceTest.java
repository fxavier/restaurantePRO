package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.redeemCommand;
import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.RedemptionResult;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The race D112 prevents (A42): two terminals redeem a member's last points at once. {@code redeem} takes
 * {@code loyalty_account FOR UPDATE} and decides on the balance read under it, so exactly one redemption succeeds and
 * the balance never goes below zero. 50 points, two rewards of 40.
 */
@LoyaltyModuleTest
class LoyaltyRedeemRaceTest {

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    LoyaltyApi api;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID member;

    @BeforeEach
    void aMemberWithFiftyPoints() throws Exception {
        loyalty.publishProgram("1.00", 1, reward("R40", 40, "FREE_UNIT", "null", null, null));
        member = loyalty.member();
        loyalty.earn(member, UUID.randomUUID(), "50.00");
    }

    /**
     * Deterministic interleaving: A has redeemed and holds its transaction open; B must wait on A's account lock (seen
     * in {@code pg_blocking_pids}), and once A commits B sees 10 points left and is refused.
     */
    @Test
    void theSecondTerminalWaitsForTheFirstAndThenSeesThePointsSpent() throws Exception {
        CountDownLatch aRedeemed = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        AtomicInteger aBackend = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<RedemptionResult> a = pool.submit(() -> tx.execute(status -> {
                RedemptionResult redeemed = api.redeem(command());
                aBackend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                aRedeemed.countDown();
                Races.await(aMayCommit);
                return redeemed;
            }));
            assertThat(aRedeemed.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Object> b = pool.submit(Races.outcome(tx, () -> api.redeem(command())));
            Races.waitUntil(() -> b.isDone() || Races.blockedBy(jdbc, aBackend.get()));

            assertThat(b.isDone()).as("B must wait for A's lock on loyalty_account, not decide on its own").isFalse();
            aMayCommit.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).points()).isEqualTo(40);
            assertThat(b.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(LoyaltyRuleException.class, refused -> {
                assertThat(refused.code()).isEqualTo("LOYALTY_INSUFFICIENT_POINTS");
                assertThat(refused.properties()).containsEntry("balance", 10L).containsEntry("required", 40);
            });
        }
        assertThat(redemptions()).isEqualTo(1);
        assertThat(loyalty.balance(member)).isEqualTo(10);
    }

    @Test
    void twoRedemptionsStartedTogetherNeverBothSucceed() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> redeem = Races.outcome(tx, () -> api.redeem(command()));
            Future<Object> first = pool.submit(() -> {
                Races.await(start);
                return redeem.call();
            });
            Future<Object> second = pool.submit(() -> {
                Races.await(start);
                return redeem.call();
            });
            start.countDown();

            List<Object> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(RedemptionResult.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(LoyaltyRuleException.class::isInstance).hasSize(1);
        }
        assertThat(redemptions()).isEqualTo(1);
        assertThat(loyalty.balance(member)).isEqualTo(10);
    }

    private com.xavier.backend.promotions.RedeemCommand command() {
        return redeemCommand(member, "R40", UUID.randomUUID(), UUID.randomUUID(), null, "15.00");
    }

    private long redemptions() {
        return jdbc.sql("SELECT count(*) FROM promotions.loyalty_point_entry WHERE customer_id = ? AND kind = 'REDEEM'")
                .param(member).query(Long.class).single();
    }
}
