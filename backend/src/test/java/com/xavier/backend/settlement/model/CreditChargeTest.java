package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D74: a credit charge goes RESERVED → POSTED or RESERVED → RELEASED, once; every other command is a 409. */
class CreditChargeTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    static final UUID ORDER = UUID.randomUUID();
    static final UUID EMPLOYEE = UUID.randomUUID();

    @Test
    void aNewChargeIsReservedForItsTarget() {
        UUID partition = UUID.randomUUID();
        CreditCharge charge = charge(partition, "25.00");

        assertThat(charge.getStatus()).isEqualTo(CreditChargeStatus.RESERVED);
        assertThat(charge.getTargetType()).isEqualTo(TargetType.PARTITION);
        assertThat(charge.getPartitionId()).isEqualTo(partition);
        assertThat(charge.amount()).isEqualTo(Money.of(new BigDecimal("25.00"), MZN));
        assertThat(charge(null, "1.00").getTargetType()).isEqualTo(TargetType.ORDER);
    }

    @Test
    void aChargeIsPositive() {
        assertThatThrownBy(() -> charge(null, "0.00")).isInstanceOf(SettlementRuleException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_AMOUNT");
    }

    @Test
    void postingNeedsItsDocumentAndKeepsTheLedgerEntry() {
        CreditCharge charge = charge(null, "25.00");
        UUID document = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        charge.attachDocument(document);

        charge.post(entry, EMPLOYEE, NOW);

        assertThat(charge.getStatus()).isEqualTo(CreditChargeStatus.POSTED);
        assertThat(charge.getLedgerEntryId()).isEqualTo(entry);
        assertThat(charge.getPostedAt()).isEqualTo(NOW);
        assertThat(charge.getPostedByEmployeeId()).isEqualTo(EMPLOYEE);
    }

    @Test
    void aChargeWithoutDocumentIsNotPosted() {
        CreditCharge charge = charge(null, "25.00");

        assertThatThrownBy(() -> charge.post(UUID.randomUUID(), EMPLOYEE, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasingKeepsWhoWhenAndWhy() {
        CreditCharge charge = charge(null, "25.00");

        charge.release("customer paid cash", EMPLOYEE, NOW);

        assertThat(charge.getStatus()).isEqualTo(CreditChargeStatus.RELEASED);
        assertThat(charge.getReleaseReason()).isEqualTo("customer paid cash");
        assertThat(charge.getReleasedAt()).isEqualTo(NOW);
    }

    @Test
    void aPostedChargeIsNeitherReleasedNorPostedAgain() {
        CreditCharge charge = charge(null, "25.00");
        charge.attachDocument(UUID.randomUUID());
        charge.post(UUID.randomUUID(), EMPLOYEE, NOW);

        assertThatThrownBy(() -> charge.release("late", EMPLOYEE, NOW))
                .isInstanceOf(SettlementConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_CHARGE_STATE");
        assertThatThrownBy(() -> charge.post(UUID.randomUUID(), EMPLOYEE, NOW))
                .isInstanceOf(SettlementConflictException.class);
    }

    @Test
    void aReleasedChargeIsNotPosted() {
        CreditCharge charge = charge(null, "25.00");
        charge.attachDocument(UUID.randomUUID());
        charge.release("mistake", EMPLOYEE, NOW);

        assertThatThrownBy(() -> charge.post(UUID.randomUUID(), EMPLOYEE, NOW))
                .isInstanceOf(SettlementConflictException.class)
                .hasFieldOrPropertyWithValue("code", "ILLEGAL_CHARGE_STATE");
    }

    @Test
    void aReleaseNeedsAReason() {
        CreditCharge charge = charge(null, "25.00");

        assertThatThrownBy(() -> charge.release(" ", EMPLOYEE, NOW)).isInstanceOf(SettlementRuleException.class)
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
    }

    @Test
    void theDocumentOfAChargeDoesNotChange() {
        CreditCharge charge = charge(null, "25.00");
        UUID document = UUID.randomUUID();
        charge.attachDocument(document);
        charge.attachDocument(document);

        assertThatThrownBy(() -> charge.attachDocument(UUID.randomUUID())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aReservedChargeIsResumedByTheSameCustomerAndAmountUnderAnyKey() {
        UUID customer = UUID.randomUUID();
        CreditCharge charge = CreditCharge.reserve(ORDER, null, UUID.randomUUID(), UUID.randomUUID(), customer,
                Money.of(new BigDecimal("10.00"), MZN), LocalDate.of(2026, 11, 5), EMPLOYEE, "key-1", NOW);

        assertThat(charge.resumableBy(customer, Money.of(new BigDecimal("10.00"), MZN), "key-2")).isTrue();
        assertThat(charge.resumableBy(customer, Money.of(new BigDecimal("10.01"), MZN), "key-1")).isFalse();
        assertThat(charge.resumableBy(UUID.randomUUID(), Money.of(new BigDecimal("10.00"), MZN), "key-1"))
                .isFalse();
    }

    @Test
    void aPostedChargeIsResumedOnlyByItsOwnKey() {
        UUID customer = UUID.randomUUID();
        CreditCharge charge = CreditCharge.reserve(ORDER, null, UUID.randomUUID(), UUID.randomUUID(), customer,
                Money.of(new BigDecimal("10.00"), MZN), LocalDate.of(2026, 11, 5), EMPLOYEE, "key-1", NOW);
        charge.attachDocument(UUID.randomUUID());
        charge.post(UUID.randomUUID(), EMPLOYEE, NOW);

        assertThat(charge.resumableBy(customer, Money.of(new BigDecimal("10.00"), MZN), "key-1")).isTrue();
        assertThat(charge.resumableBy(customer, Money.of(new BigDecimal("10.00"), MZN), "key-2")).isFalse();
    }

    static CreditCharge charge(UUID partition, String amount) {
        return CreditCharge.reserve(ORDER, partition, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.of(new BigDecimal(amount), MZN), LocalDate.of(2026, 11, 5), EMPLOYEE, "key", NOW);
    }
}
