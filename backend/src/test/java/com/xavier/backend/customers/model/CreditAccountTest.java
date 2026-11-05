package com.xavier.backend.customers.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditAccountTest {

    static final Currency MZN = Currency.getInstance("MZN");
    final Instant now = Instant.parse("2026-11-05T10:00:00Z");

    @Test
    void aNewAccountHasAZeroLimitAndChangingItReturnsTheOldOne() {
        CreditAccount account = CreditAccount.open(UUID.randomUUID(), MZN, now);

        Money old = account.changeLimit(Money.of("100.00", MZN));

        assertThat(old).isEqualTo(Money.of("0.00", MZN));
        assertThat(account.limit()).isEqualTo(Money.of("100.00", MZN));
    }

    @Test
    void aLimitIsNeverNegativeNorInAnotherCurrency() {
        CreditAccount account = CreditAccount.open(UUID.randomUUID(), MZN, now);

        assertThatThrownBy(() -> account.changeLimit(Money.of("-1.00", MZN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.changeLimit(Money.of("1.00", Currency.getInstance("EUR"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlockNeedsAReasonAndAnUnblockClearsIt() {
        CreditAccount account = CreditAccount.open(UUID.randomUUID(), MZN, now);
        assertThatThrownBy(() -> account.block(" ", now)).isInstanceOf(IllegalArgumentException.class);

        account.block(" unpaid since June ", now);
        assertThat(account.isCreditBlocked()).isTrue();
        assertThat(account.getBlockedReason()).isEqualTo("unpaid since June");
        assertThat(account.getBlockedAt()).isEqualTo(now);

        account.unblock();
        assertThat(account.isCreditBlocked()).isFalse();
        assertThat(account.getBlockedReason()).isNull();
        assertThat(account.getBlockedAt()).isNull();
    }
}
