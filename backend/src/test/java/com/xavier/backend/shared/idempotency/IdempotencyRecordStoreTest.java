package com.xavier.backend.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.UncategorizedDataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;

/**
 * Only "the row lock is held by a live owner" may become 409 in progress. Spring maps deadlocks and
 * serialization failures to the same {@link PessimisticLockingFailureException} family, so the decision must
 * rest on the SQLSTATE. A real 40P01/40001 cannot be provoked on {@code FOR UPDATE NOWAIT} (it never waits and
 * runs first in its own transaction), so the classifier is tested with the exceptions Spring would raise.
 */
class IdempotencyRecordStoreTest {

    @Test
    void lockNotAvailableMeansAnotherRequestHoldsTheKey() {
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new CannotAcquireLockException("nowait", sql("55P03")))).isTrue();
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new UncategorizedSQLException("nowait", "SELECT", sql("55P03")))).isTrue();
    }

    @Test
    void aDeadlockIsNotMistakenForARequestInProgress() {
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new PessimisticLockingFailureException("deadlock", sql("40P01")))).isFalse();
    }

    @Test
    void aSerializationFailureIsNotMistakenForARequestInProgress() {
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new PessimisticLockingFailureException("serialization", sql("40001")))).isFalse();
    }

    @Test
    void aLockingFailureWithoutSqlStateIsNotMistakenForARequestInProgress() {
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new PessimisticLockingFailureException("no cause"))).isFalse();
        assertThat(IdempotencyRecordStore.isLockNotAvailable(
                new UncategorizedDataAccessException("other", sql("23505")) { })).isFalse();
    }

    private static SQLException sql(String sqlState) {
        return new SQLException("probe " + sqlState, sqlState);
    }
}
