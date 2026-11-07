package com.xavier.backend.inventory.purchasing.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Receipt progress is derived from what arrived (D124), per line and per order. */
class ReceiptProgressTest {

    @Test
    void aLineIsNoneThenPartialThenFullAndAnOverReceiptIsFull() {
        assertThat(ReceiptProgress.of(new BigDecimal("10"), BigDecimal.ZERO)).isEqualTo(ReceiptProgress.NONE);
        assertThat(ReceiptProgress.of(new BigDecimal("10"), new BigDecimal("4"))).isEqualTo(ReceiptProgress.PARTIAL);
        assertThat(ReceiptProgress.of(new BigDecimal("10"), new BigDecimal("10.000000")))
                .isEqualTo(ReceiptProgress.FULL);
        assertThat(ReceiptProgress.of(new BigDecimal("10"), new BigDecimal("12"))).isEqualTo(ReceiptProgress.FULL);
    }

    @Test
    void anOrderIsFullOnlyWhenEveryLineIsAndNoneOnlyWhenNoLineReceivedAnything() {
        assertThat(ReceiptProgress.ofOrder(List.of(ReceiptProgress.NONE, ReceiptProgress.NONE)))
                .isEqualTo(ReceiptProgress.NONE);
        assertThat(ReceiptProgress.ofOrder(List.of(ReceiptProgress.FULL, ReceiptProgress.NONE)))
                .isEqualTo(ReceiptProgress.PARTIAL);
        assertThat(ReceiptProgress.ofOrder(List.of(ReceiptProgress.PARTIAL, ReceiptProgress.FULL)))
                .isEqualTo(ReceiptProgress.PARTIAL);
        assertThat(ReceiptProgress.ofOrder(List.of(ReceiptProgress.FULL, ReceiptProgress.FULL)))
                .isEqualTo(ReceiptProgress.FULL);
    }
}
