package com.xavier.backend.customers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.customers.model.CustomerQuery.Kind;
import org.junit.jupiter.api.Test;

class CustomerQueryTest {

    @Test
    void digitsAreAPhoneSuffixOfAtLeastFourDigits() {
        assertThat(CustomerQuery.of(" 45-67 ")).isEqualTo(new CustomerQuery(Kind.PHONE_SUFFIX, "4567"));
        assertThat(CustomerQuery.isValid("456")).isFalse();
        assertThat(CustomerQuery.isValid("+4567")).isTrue();
    }

    @Test
    void textIsANameOfAtLeastThreeCharacters() {
        assertThat(CustomerQuery.of("  Joã ")).isEqualTo(new CustomerQuery(Kind.NAME, "Joã"));
        assertThat(CustomerQuery.of("ana silva")).isEqualTo(new CustomerQuery(Kind.NAME, "ana silva"));
        assertThat(CustomerQuery.isValid("jo")).isFalse();
        assertThat(CustomerQuery.isValid("   ")).isFalse();
        assertThat(CustomerQuery.isValid(null)).isFalse();
    }

    @Test
    void anInvalidQueryIsRefused() {
        assertThatThrownBy(() -> CustomerQuery.of("12")).isInstanceOf(IllegalArgumentException.class);
    }
}
