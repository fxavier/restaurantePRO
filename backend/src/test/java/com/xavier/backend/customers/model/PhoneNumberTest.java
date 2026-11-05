package com.xavier.backend.customers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhoneNumberTest {

    @Test
    void keepsOnlyTheDigits() {
        assertThat(PhoneNumber.of("+258 84-123 (4567)").digits()).isEqualTo("258841234567");
    }

    @Test
    void needsFourToTwentyDigits() {
        assertThat(PhoneNumber.of("1234").digits()).isEqualTo("1234");
        assertThatThrownBy(() -> PhoneNumber.of("12 3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhoneNumber.of("1".repeat(21))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhoneNumber.of("84 abc 1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PhoneNumber.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
