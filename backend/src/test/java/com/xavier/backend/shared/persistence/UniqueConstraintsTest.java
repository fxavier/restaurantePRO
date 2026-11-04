package com.xavier.backend.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** D43: the one copy of the unique-constraint and stale-version helpers (each module's race tests use it). */
class UniqueConstraintsTest {

    @Test
    void recognisesOnlyTheNamedConstraints() {
        DataIntegrityViolationException duplicateCode = violation("store_code_uk");

        assertThat(UniqueConstraints.isViolated(duplicateCode, "store_code_uk")).isTrue();
        assertThat(UniqueConstraints.isViolated(duplicateCode, "other_uk", "STORE_CODE_UK")).isTrue();
        assertThat(UniqueConstraints.isViolated(violation("store_name_not_blank_ck"), "store_code_uk")).isFalse();
        assertThat(UniqueConstraints.isViolated(violation(null), "store_code_uk")).isFalse();
        assertThat(UniqueConstraints.isViolated(new DataIntegrityViolationException("no cause"), "store_code_uk"))
                .isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveAndFlushTranslatesOnlyTheNamedConstraints() {
        JpaRepository<String, Object> repository = mock(JpaRepository.class);
        when(repository.saveAndFlush(any())).thenThrow(violation("item_code_uk"));

        assertThatThrownBy(() -> UniqueConstraints.saveAndFlush(repository, "item",
                () -> new IllegalArgumentException("code taken"), "item_code_uk", "item_name_uk"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("code taken");
        assertThatThrownBy(() -> UniqueConstraints.saveAndFlush(repository, "item",
                () -> new IllegalArgumentException("code taken"), "other_uk"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aStaleVersionIsAnOptimisticLockingFailure() {
        OptimisticLocking.requireVersion(String.class, "id", 3, 3);

        assertThatThrownBy(() -> OptimisticLocking.requireVersion(String.class, "id", 4, 3))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("integrity",
                new ConstraintViolationException("violated", new SQLException("x", "23505"), constraint));
    }
}
