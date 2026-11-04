package com.xavier.backend.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class UniqueConstraintTest {

    @Test
    void recognisesOnlyTheNamedConstraint() {
        DataIntegrityViolationException duplicateCode = violation("store_code_uk");

        assertThat(UniqueConstraint.isViolated(duplicateCode, "store_code_uk")).isTrue();
        assertThat(UniqueConstraint.isViolated(violation("store_name_not_blank_ck"), "store_code_uk")).isFalse();
        assertThat(UniqueConstraint.isViolated(new DataIntegrityViolationException("no cause"), "store_code_uk"))
                .isFalse();
    }

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("integrity",
                new ConstraintViolationException("violated", new SQLException("x", "23505"), constraint));
    }
}
