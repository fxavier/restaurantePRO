package com.xavier.backend.orders.model;

import static com.xavier.backend.orders.model.OrderLineTest.EMPLOYEE;
import static com.xavier.backend.orders.model.OrderLineTest.NOW;
import static com.xavier.backend.orders.model.OrderLineTest.SNAPSHOT;
import static com.xavier.backend.orders.model.OrderLineTest.draft;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * S14: taking quantity off a confirmed line (a void or a transfer out) and the confirmed copy a transfer puts on the
 * target order (§8.2: no new round, so production and stock never see it twice).
 */
class OrderLineTakingTest {

    static OrderLine confirmed() {
        OrderLine line = draft(); // quantity 2
        line.confirm(SNAPSHOT, UUID.randomUUID(), EMPLOYEE, NOW);
        return line;
    }

    @Test
    void aConfirmedLineGivesUpToItsNetQuantity() {
        OrderLine line = confirmed();

        line.requireAvailable(new BigDecimal("0.5"), new BigDecimal("1.5"));
    }

    @Test
    void takingMoreThanTheNetQuantityIsRefused() {
        OrderLine line = confirmed();

        assertThatThrownBy(() -> line.requireAvailable(new BigDecimal("0.5"), new BigDecimal("1.501")))
                .isInstanceOfSatisfying(OrderRuleException.class,
                        e -> assertThat(e.code()).isEqualTo("QUANTITY_EXCEEDS_NET"));
    }

    @Test
    void aDraftHasNothingToTake() {
        assertThatThrownBy(() -> draft().requireAvailable(BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOfSatisfying(OrderConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("LINE_NOT_CONFIRMED"));
    }

    @Test
    void theTakenQuantityIsPositiveWithAtMostThreeDecimals() {
        assertThatThrownBy(() -> confirmed().requireAvailable(BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> confirmed().requireAvailable(BigDecimal.ZERO, new BigDecimal("0.0001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTransferredCopyIsConfirmedWithTheSourceSnapshotAndNoRound() {
        OrderLine source = confirmed();
        UUID target = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        UUID mover = UUID.randomUUID();

        OrderLine copy = OrderLine.transferredCopy(source, target, new BigDecimal("1.5"), transfer, mover, NOW);

        assertThat(copy.getOrderId()).isEqualTo(target);
        assertThat(copy.getStatus()).isEqualTo(LineStatus.CONFIRMED);
        assertThat(copy.snapshot()).contains(SNAPSHOT);
        assertThat(copy.getQuantity()).isEqualByComparingTo("1.5");
        assertThat(copy.getItemId()).isEqualTo(source.getItemId());
        assertThat(copy.getNotes()).isEqualTo(source.getNotes());
        assertThat(copy.getBatchId()).isNull();
        assertThat(copy.getOriginTransferId()).isEqualTo(transfer);
        assertThat(copy.getConfirmedByEmployeeId()).isEqualTo(mover);
        assertThat(copy.getConfirmedAt()).isEqualTo(NOW);
    }
}
