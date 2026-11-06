package com.xavier.backend.promotions.loyalty;

import static com.xavier.backend.promotions.loyalty.LoyaltyFixtures.reward;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.promotions.RedemptionResult;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D110: debits are allocated to the point lots they consume, first in first out by {@code occurred_at}, so an
 * expiry rule can later read the remaining points of each lot. The balance is always Σ entries. The database checks
 * at commit that a debit is exactly allocated and that no lot gives more than it has.
 */
@LoyaltyModuleTest
class LoyaltyLedgerAllocationTest {

    @Autowired
    LoyaltyFixtures loyalty;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MutableClock clock;

    UUID member;

    @BeforeEach
    void aProgramOfOnePointPerMeticalAndAMember() throws Exception {
        loyalty.publishProgram("1.00", 1, reward("R40", 40, "FREE_UNIT", "null", null, null),
                reward("R25", 25, "FREE_UNIT", "null", null, null));
        member = loyalty.member();
    }

    @Test
    void redemptionsConsumeTheOldestPointsFirst() {
        UUID first = loyalty.earn(member, UUID.randomUUID(), "30.00").orElseThrow().entryId();
        clock.advance(Duration.ofMinutes(1));
        UUID second = loyalty.earn(member, UUID.randomUUID(), "50.00").orElseThrow().entryId();
        clock.advance(Duration.ofMinutes(1));

        RedemptionResult forty = loyalty.redeem(member, "R40", "12.00");
        RedemptionResult twentyFive = loyalty.redeem(member, "R25", "8.00");

        assertThat(allocations(forty.entryId())).containsExactly(first + ":30", second + ":10");
        assertThat(allocations(twentyFive.entryId())).containsExactly(second + ":25");
        assertThat(loyalty.balance(member)).isEqualTo(15).isEqualTo(sumOfEntries());
    }

    @Test
    void theBalanceIsAlwaysTheSumOfTheEntries() {
        loyalty.earn(member, UUID.randomUUID(), "45.00");
        loyalty.redeem(member, "R40", "3.00");

        assertThat(loyalty.balance(member)).isEqualTo(5).isEqualTo(sumOfEntries());
    }

    @Test
    void aDebitWithoutAllocationsIsRefusedAtCommit() {
        loyalty.earn(member, UUID.randomUUID(), "30.00");

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> insertAdjustment(UUID.randomUUID(), -10)))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("has 0 allocated");
        assertThat(loyalty.balance(member)).isEqualTo(30);
    }

    @Test
    void aDebitAllocatedForMoreThanItsPointsIsRefusedAtCommit() {
        UUID lot = loyalty.earn(member, UUID.randomUUID(), "30.00").orElseThrow().entryId();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID debit = UUID.randomUUID();
            insertAdjustment(debit, -10);
            allocate(debit, lot, 12);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("has 12 allocated");
    }

    @Test
    void aLotGivingMoreThanItHasIsRefusedAtCommit() {
        UUID lot = loyalty.earn(member, UUID.randomUUID(), "30.00").orElseThrow().entryId();
        loyalty.earn(member, UUID.randomUUID(), "30.00");
        clock.advance(Duration.ofSeconds(1));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID debit = UUID.randomUUID();
            insertAdjustment(debit, -40);
            allocate(debit, lot, 40);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("would give 40");
        assertThat(loyalty.balance(member)).isEqualTo(60);
    }

    @Test
    void aDebitCannotConsumeALotOfAnotherMember() throws Exception {
        UUID other = loyalty.member();
        UUID othersLot = loyalty.earn(other, UUID.randomUUID(), "30.00").orElseThrow().entryId();
        loyalty.earn(member, UUID.randomUUID(), "30.00");
        clock.advance(Duration.ofSeconds(1));

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID debit = UUID.randomUUID();
            insertAdjustment(debit, -10);
            allocate(debit, othersLot, 10);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("does not link a debit");
    }

    @Test
    void entriesAndAllocationsAreNeitherUpdatedNorDeleted() {
        loyalty.earn(member, UUID.randomUUID(), "45.00");
        RedemptionResult redeemed = loyalty.redeem(member, "R40", "3.00");

        assertRefused("UPDATE promotions.loyalty_point_entry SET points = 1 WHERE customer_id = ?", member);
        assertRefused("DELETE FROM promotions.loyalty_point_entry WHERE customer_id = ?", member);
        assertRefused("UPDATE promotions.loyalty_allocation SET points = 1 WHERE debit_entry_id = ?",
                redeemed.entryId());
        assertRefused("DELETE FROM promotions.loyalty_allocation WHERE debit_entry_id = ?", redeemed.entryId());
    }

    private void insertAdjustment(UUID id, int points) {
        jdbc.sql("""
                        INSERT INTO promotions.loyalty_point_entry (id, customer_id, kind, points, source_type, source_id,
                            reason, actor_kind, occurred_at)
                        VALUES (?, ?, 'ADJUSTMENT', ?, 'ADJUSTMENT', ?, 'manual', 'SYSTEM', ?)""")
                .params(id, member, points, id, java.sql.Timestamp.from(clock.instant())).update();
    }

    private void allocate(UUID debit, UUID lot, int points) {
        jdbc.sql("INSERT INTO promotions.loyalty_allocation (debit_entry_id, earn_entry_id, points) VALUES (?, ?, ?)")
                .params(debit, lot, points).update();
    }

    private List<String> allocations(UUID debit) {
        return jdbc.sql("""
                        SELECT a.earn_entry_id || ':' || a.points FROM promotions.loyalty_allocation a
                        JOIN promotions.loyalty_point_entry e ON e.id = a.earn_entry_id
                        WHERE a.debit_entry_id = ? ORDER BY e.occurred_at, e.id""")
                .param(debit).query(String.class).list();
    }

    private long sumOfEntries() {
        return jdbc.sql("SELECT coalesce(sum(points), 0) FROM promotions.loyalty_point_entry WHERE customer_id = ?")
                .param(member).query(Long.class).single();
    }

    private void assertRefused(String sql, Object param) {
        assertThatThrownBy(() -> jdbc.sql(sql).param(param).update()).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }
}
