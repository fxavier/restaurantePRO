package com.xavier.backend.orders;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The void facts reporting pulls (D84): one void by id, and the paged ids of a store's voids by the order's business
 * day, which a rebuild walks.
 */
@OrdersModuleTest
class VoidFactsTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrdersProductionApi productionApi;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID approver = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;

    @BeforeEach
    void aRoundOfThree() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
    }

    @Test
    void theFactsOfAVoidAfterTheRoundWasDispatched() {
        dispatch(line);
        VoidView voided = voidOf(order, line, 1, "2");

        VoidFacts facts = inPos(() -> ordersApi.voidFacts(voided.voidId()));

        assertThat(facts.voidId()).isEqualTo(voided.voidId());
        assertThat(facts.orderId()).isEqualTo(order);
        assertThat(facts.storeId()).isEqualTo(shop.store());
        assertThat(facts.businessDay()).isEqualTo(voided.order().businessDay());
        assertThat(facts.at()).isEqualTo(voided.voidedAt());
        assertThat(facts.lineSnapshot().lineId()).isEqualTo(line);
        assertThat(facts.lineSnapshot().itemId()).isEqualTo(shop.item());
        assertThat(facts.lineSnapshot().itemCode()).isEqualTo(column("item_code"));
        assertThat(facts.lineSnapshot().description()).isEqualTo("Coca-Cola 33cl");
        assertThat(facts.lineSnapshot().unitPrice()).isEqualTo(Money.of("12.50", MZN));
        assertThat(facts.lineSnapshot().taxRatePercent()).isEqualByComparingTo("16");
        assertThat(facts.lineSnapshot().releaseId()).hasToString(column("release_id"));
        assertThat(facts.quantity()).isEqualByComparingTo("2");
        assertThat(facts.gross()).isEqualTo(Money.of("25.00", MZN));
        assertThat(facts.reason()).isEqualTo("wrong dish");
        assertThat(facts.wasted()).isTrue();
        assertThat(facts.operatorEmployeeId()).isEqualTo(operator);
        assertThat(facts.approverEmployeeId()).isEqualTo(approver);
        assertThat(facts.afterDispatch()).isTrue();
    }

    @Test
    void aVoidBeforeTheRoundWasDispatchedIsNotAfterDispatch() {
        VoidView voided = voidOf(order, line, 1, "1");

        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).afterDispatch()).isFalse();
    }

    @Test
    void aVoidOfALineTransferredInFollowsItsOriginRound() throws Exception {
        dispatch(line);
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        fixtures.transfer(shop, order, 1, target, 0, Map.of(line, "1"), false, operator, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        UUID copy = jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();
        long targetVersion = inPos(() -> ordersApi.billingView(target)).version();

        VoidView voided = voidOf(target, copy, targetVersion, "1");

        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).afterDispatch()).isTrue();
    }

    @Test
    void aVoidAfterTwoTransfersFollowsBackToTheOriginalRound() throws Exception {
        dispatch(line);
        UUID middle = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), operator, terminal);
        UUID last = fixtures.openedAt(shop, fixtures.newTable(shop, "M3"), operator, terminal);
        UUID middleCopy = transferOne(order, line, middle);
        UUID lastCopy = transferOne(middle, middleCopy, last);

        VoidView voided = voidOf(last, lastCopy, inPos(() -> ordersApi.billingView(last)).version(), "1");

        assertThat(inPos(() -> ordersApi.voidFacts(voided.voidId())).afterDispatch()).isTrue();
    }

    /** Transfers 1 of {@code sourceLine} to {@code target}; the copy's id on the target. */
    UUID transferOne(UUID source, UUID sourceLine, UUID target) throws Exception {
        fixtures.transfer(shop, source, inPos(() -> ordersApi.billingView(source)).version(), target,
                inPos(() -> ordersApi.billingView(target)).version(), Map.of(sourceLine, "1"), false, operator,
                terminal, UUID.randomUUID().toString()).andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();
    }

    @Test
    void anUnknownVoidIsNotFound() {
        assertThatThrownBy(() -> inPos(() -> ordersApi.voidFacts(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theVoidIdsOfAStoreByBusinessDayInCreationOrder() throws Exception {
        VoidView first = voidOf(order, line, 1, "1");
        VoidView second = voidOf(order, line, 2, "1");
        Shop otherShop = fixtures.shop();
        UUID otherOrder = id(fixtures.openedOrder(otherShop, operator, terminal));
        UUID otherLine = fixtures.confirmedLine(otherShop, otherOrder, "1", 0, operator, terminal);
        OrdersActors.runAsPos(otherShop.store(), operator, terminal, () -> new TransactionTemplate(transactionManager)
                .execute(status -> ordersApi.voidLine(new SettledVoidCommand(otherOrder, 1, otherLine,
                        BigDecimal.ONE, "wrong dish", false, operator), approver)));
        LocalDate day = first.order().businessDay();

        Page<UUID> page = inPos(() -> ordersApi.voidIds(shop.store(), day, day, PageRequest.of(0, 1)));
        Page<UUID> next = inPos(() -> ordersApi.voidIds(shop.store(), day, day, PageRequest.of(1, 1)));

        assertThat(page.getContent()).containsExactly(first.voidId());
        assertThat(next.getContent()).containsExactly(second.voidId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(inPos(() -> ordersApi.voidIds(shop.store(), day.minusDays(2), day.minusDays(1),
                PageRequest.of(0, 10))).getContent()).isEmpty();
    }

    VoidView voidOf(UUID orderId, UUID lineId, long version, String quantity) {
        return inPos(() -> ordersApi.voidLine(new SettledVoidCommand(orderId, version, lineId,
                new BigDecimal(quantity), "wrong dish", true, operator), approver));
    }

    void dispatch(UUID confirmedLine) {
        UUID batch = jdbc.sql("SELECT batch_id FROM orders.order_line WHERE id = ?").param(confirmedLine)
                .query(UUID.class).single();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> productionApi.markBatchDispatched(batch));
    }

    String column(String name) {
        return jdbc.sql("SELECT " + name + "::text FROM orders.order_line WHERE id = ?").param(line)
                .query(String.class).single();
    }

    <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
