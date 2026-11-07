package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.ACCOUNTS;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.promotions.EarnCommand;
import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.EarnResult;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D111: an order earns once, inside its close, under the program version in force at the close instant
 * ({@code UNIQUE(ORDER, order_id, EARN)}): a resumed close earns nothing more, and a repeat with other facts is a 409
 * {@code LOYALTY_SOURCE_MISMATCH}, never a silent success. Nothing is earned without an ACTIVE account, without a
 * program in force, or for 0 points.
 */
@LoyaltyModuleTest
class LoyaltyEarnIdempotencyTest {

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    LoyaltyApi api;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MutableClock clock;

    @Autowired
    MockMvc mvc;

    UUID program;
    UUID member;
    UUID order;

    @BeforeEach
    void aProgramOfOnePointPerTenMeticaisAndAMember() throws Exception {
        program = loyalty.publishProgram("10.00", 1);
        member = loyalty.member();
        order = UUID.randomUUID();
    }

    @Test
    void anOrderEarnsOnceAndARepeatReturnsTheSameEntry() {
        EarnResult first = loyalty.earn(member, order, "57.00").orElseThrow();
        EarnResult again = loyalty.earn(member, order, "57.00").orElseThrow();

        assertThat(first.points()).isEqualTo(5);
        assertThat(first.programVersionId()).isEqualTo(program);
        assertThat(again).isEqualTo(first);
        assertThat(earnings()).isEqualTo(1);
        assertThat(loyalty.balance(member)).isEqualTo(5);
    }

    @Test
    void aRepeatWithOtherFactsIsAConflictAndChangesNothing() {
        loyalty.earn(member, order, "57.00");

        assertThatThrownBy(() -> loyalty.earn(member, order, "99.00"))
                .isInstanceOfSatisfying(LoyaltyConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("LOYALTY_SOURCE_MISMATCH"));
        assertThat(earnings()).isEqualTo(1);
        assertThat(loyalty.balance(member)).isEqualTo(5);
    }

    @Test
    void theVersionInForceAtTheCloseInstantEarnsEvenIfRetiredSince() throws Exception {
        Instant closedAt = clock.instant();
        clock.advance(Duration.ofMinutes(5));
        loyalty.publishProgram("1.00", 3);

        EarnResult earned = tx.execute(status -> api.earnForOrder(new EarnCommand(order, LoyaltyFixtures.STORE,
                member, LoyaltyFixtures.DAY, closedAt, List.of(new EarnLine(UUID.randomUUID(), null,
                LoyaltyFixtures.mzn("57.00"), false))))).orElseThrow();

        assertThat(earned.programVersionId()).isEqualTo(program);
        assertThat(earned.points()).isEqualTo(5);
    }

    @Test
    void nothingIsEarnedWithoutAProgramInForceAtTheCloseInstant() {
        Optional<EarnResult> earned = tx.execute(status -> api.earnForOrder(new EarnCommand(order,
                LoyaltyFixtures.STORE, member, LoyaltyFixtures.DAY, Instant.EPOCH,
                List.of(new EarnLine(UUID.randomUUID(), null, LoyaltyFixtures.mzn("57.00"), false)))));

        assertThat(earned).isEmpty();
        assertThat(earnings()).isZero();
    }

    @Test
    void nothingIsEarnedForZeroPointsOrByANonMember() throws Exception {
        UUID notEnrolled = loyalty.customer();

        assertThat(loyalty.earn(member, order, "9.99")).isEmpty();
        assertThat(loyalty.earn(notEnrolled, UUID.randomUUID(), "500.00")).isEmpty();
        assertThat(earnings()).isZero();
    }

    @Test
    void aSuspendedMemberEarnsNothing() throws Exception {
        mvc.perform(idem(post(ACCOUNTS + "/" + member + "/suspend")).with(account(null, Permission.LOYALTY_ENROLL))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "card shared"}"""))
                .andExpect(status().isOk());

        assertThat(loyalty.earn(member, order, "500.00")).isEmpty();
    }

    @Test
    void earningJoinsTheCallersTransactionOnly() {
        assertThatThrownBy(() -> api.earnForOrder(loyalty.earnCommand(member, order, "57.00")))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /**
     * Two earnings of one order at once (orders' claim normally prevents it): the second waits on the first's unique
     * index entry, then is a 409 {@code LOYALTY_SOURCE_CONCURRENT}; a retry sees the first's entry.
     */
    @Test
    void aConcurrentEarningOfTheSameOrderIsAConflictThenAReplay() throws Exception {
        CountDownLatch aEarned = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        AtomicInteger aBackend = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<EarnResult> a = pool.submit(() -> tx.execute(status -> {
                EarnResult earned = api.earnForOrder(loyalty.earnCommand(member, order, "57.00")).orElseThrow();
                aBackend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                aEarned.countDown();
                Races.await(aMayCommit);
                return earned;
            }));
            assertThat(aEarned.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Object> b = pool.submit(Races.outcome(tx, () -> api.earnForOrder(
                    loyalty.earnCommand(member, order, "57.00"))));
            Races.waitUntil(() -> b.isDone() || Races.blockedBy(jdbc, aBackend.get()));

            assertThat(b.isDone()).as("B must wait for A's entry, not decide on its own").isFalse();
            aMayCommit.countDown();
            EarnResult first = a.get(30, TimeUnit.SECONDS);
            assertThat(b.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(LoyaltyConflictException.class,
                    e -> assertThat(e.code()).isEqualTo("LOYALTY_SOURCE_CONCURRENT"));
            assertThat(loyalty.earn(member, order, "57.00")).contains(first);
        }
        assertThat(earnings()).isEqualTo(1);
    }

    private long earnings() {
        return jdbc.sql("""
                        SELECT count(*) FROM promotions.loyalty_point_entry
                        WHERE source_type = 'ORDER' AND source_id = ? AND kind = 'EARN'""")
                .param(order).query(Long.class).single();
    }
}
