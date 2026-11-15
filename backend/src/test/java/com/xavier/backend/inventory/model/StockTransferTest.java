package com.xavier.backend.inventory.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The transfer machine (D120): {@code DRAFT → SHIPPED → RECEIVED}, {@code DRAFT → CANCELLED}, every other 409. */
class StockTransferTest {

    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    UUID item;
    UUID other;

    @BeforeEach
    void items() {
        item = UUID.randomUUID();
        other = UUID.randomUUID();
    }

    @Test
    void aTransferGoesToAnotherWarehouse() {
        UUID warehouse = UUID.randomUUID();
        UUID store = UUID.randomUUID();
        assertThatThrownBy(() -> StockTransfer.draft(warehouse, store, warehouse, store,
                List.of(TransferLine.of(item, BigDecimal.ONE)), null, null, NOW))
                .isInstanceOfSatisfying(InventoryRuleException.class,
                        refused -> assertThat(refused.code()).isEqualTo("SAME_WAREHOUSE"));
    }

    @Test
    void aDraftNeedsLinesWithDistinctItems() {
        assertThatThrownBy(() -> draft(List.of()))
                .isInstanceOfSatisfying(InventoryRuleException.class,
                        refused -> assertThat(refused.code()).isEqualTo("INVALID_QUANTITY"));
        assertThatThrownBy(() -> draft(List.of(TransferLine.of(item, BigDecimal.ONE),
                TransferLine.of(item, BigDecimal.TWO))))
                .isInstanceOfSatisfying(InventoryRuleException.class,
                        refused -> assertThat(refused.code()).isEqualTo("DUPLICATE_STOCK_ITEM"));
    }

    @Test
    void shippedThenReceivedWithAShortfall() {
        StockTransfer transfer = draft(List.of(TransferLine.of(item, new BigDecimal("10")),
                TransferLine.of(other, new BigDecimal("2"))));

        transfer.ship(null, NOW);
        transfer.recordShipping(Map.of(item, new TransferLine.Shipped(new BigDecimal("3.500000"),
                new BigDecimal("35.0000")), other, new TransferLine.Shipped(new BigDecimal("1.000000"),
                new BigDecimal("2.0000"))));
        transfer.receive(Map.of(item, new BigDecimal("8"), other, new BigDecimal("2")), null, NOW);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.RECEIVED);
        assertThat(transfer.getLines()).extracting(TransferLine::shortfall)
                .containsExactly(new BigDecimal("2.000000"), new BigDecimal("0.000000"));
        assertThat(transfer.getLines().getFirst().getUnitCost()).isEqualByComparingTo("3.5");
    }

    @Test
    void shippingCostsAreRecordedOnlyOnceShipped() {
        StockTransfer transfer = draft(List.of(TransferLine.of(item, BigDecimal.TEN)));

        assertThatThrownBy(() -> transfer.recordShipping(Map.of(item, new TransferLine.Shipped(BigDecimal.ONE,
                BigDecimal.TEN)))).isInstanceOf(InventoryConflictException.class);
    }

    /**
     * D113 as amended under RLS (D168): the value each line's OUT took is kept on the line, which both stores see, so
     * the destination's IN carries it without reading the source store's ledger.
     */
    @Test
    void eachLineKeepsTheValueItsOutTook() {
        StockTransfer transfer = draft(List.of(TransferLine.of(item, new BigDecimal("3"))));
        transfer.ship(null, NOW);

        transfer.recordShipping(Map.of(item, new TransferLine.Shipped(new BigDecimal("3.333333"),
                new BigDecimal("10.0000"))));

        assertThat(transfer.getLines().getFirst().getValueShipped()).isEqualByComparingTo("10");
        assertThat(transfer.getLines().getFirst().getUnitCost()).isEqualByComparingTo("3.333333");
    }

    @Test
    void receivingMoreThanShippedIsRefused() {
        StockTransfer transfer = shipped();

        assertThatThrownBy(() -> transfer.receive(Map.of(item, new BigDecimal("10.000001")), null, NOW))
                .isInstanceOfSatisfying(InventoryRuleException.class, refused -> {
                    assertThat(refused.code()).isEqualTo("RECEIVED_EXCEEDS_SHIPPED");
                    assertThat(refused.properties()).containsEntry("stockItemId", item);
                });
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.SHIPPED);
    }

    @Test
    void everyShippedLineNeedsAReceivedQuantity() {
        StockTransfer transfer = shipped();

        assertThatThrownBy(() -> transfer.receive(Map.of(), null, NOW))
                .isInstanceOfSatisfying(InventoryRuleException.class,
                        refused -> assertThat(refused.code()).isEqualTo("INVALID_QUANTITY"));
        assertThatThrownBy(() -> transfer.receive(Map.of(item, BigDecimal.ONE, other, BigDecimal.ONE), null, NOW))
                .isInstanceOf(InventoryRuleException.class);
    }

    @ParameterizedTest
    @EnumSource(TransferStatus.class)
    void onlyTheMachineTransitionsAreAllowed(TransferStatus status) {
        assertAllowed(status, TransferStatus.DRAFT, t -> t.edit(List.of(TransferLine.of(item, BigDecimal.ONE)), "n"));
        assertAllowed(status, TransferStatus.DRAFT, t -> t.ship(null, NOW));
        assertAllowed(status, TransferStatus.DRAFT, t -> t.cancel(NOW));
        assertAllowed(status, TransferStatus.SHIPPED, t -> t.receive(Map.of(item, BigDecimal.ONE), null, NOW));
    }

    private void assertAllowed(TransferStatus status, TransferStatus allowedFrom, Consumer<StockTransfer> command) {
        StockTransfer transfer = in(status);
        if (status == allowedFrom) {
            command.accept(transfer);
        } else {
            assertThatThrownBy(() -> command.accept(transfer))
                    .isInstanceOfSatisfying(InventoryConflictException.class,
                            conflict -> assertThat(conflict.code()).isEqualTo("TRANSFER_STATE"));
            assertThat(transfer.getStatus()).isEqualTo(status);
        }
    }

    private StockTransfer in(TransferStatus status) {
        StockTransfer transfer = draft(List.of(TransferLine.of(item, BigDecimal.TEN)));
        switch (status) {
            case DRAFT -> {
            }
            case SHIPPED -> transfer.ship(null, NOW);
            case RECEIVED -> {
                transfer.ship(null, NOW);
                transfer.receive(Map.of(item, BigDecimal.TEN), null, NOW);
            }
            case CANCELLED -> transfer.cancel(NOW);
        }
        return transfer;
    }

    private StockTransfer shipped() {
        StockTransfer transfer = draft(List.of(TransferLine.of(item, BigDecimal.TEN)));
        transfer.ship(null, NOW);
        return transfer;
    }

    private StockTransfer draft(List<TransferLine> lines) {
        return StockTransfer.draft(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                lines, null, null, NOW);
    }
}
