package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.DiscountFacts;
import com.xavier.backend.orders.OfferFacts;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import com.xavier.backend.orders.SettledVoidCommand;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S53: a line's price origin follows its lineage. A split or transferred copy of an offered line is offered too (its
 * bill says "Oferta"), a reversal on such a copy takes back the offer, and a closed order's offer facts are its
 * offered billing lines at their net quantity, each with the round line whose consumption carries its cost.
 */
@OrdersModuleTest
class OfferLineageTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;
    UUID other;
    UUID discounted;
    Employee manager;

    @BeforeEach
    void threeCoffeesAllOffered() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = fixtures.confirmedLine(shop, order, "3", 0, operator, terminal);
        other = fixtures.openedAt(shop, fixtures.newTable(shop, "O" + UUID.randomUUID().toString().substring(0, 5)),
                operator, terminal);
        discounted = fixtures.confirmedLine(shop, other, "2", 0, operator, terminal);
        promotions.publish(shop.store()); // the default limits (D106)
        manager = fixtures.employee(shop, IdentityFixtures.MANAGER);
        adjust(order, line, null, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, manager).andExpect(status().isOk());
    }

    @Test
    void aTransferredCopyOfAnOfferedLineIsAnOffer() throws Exception {
        UUID target = transferOne();

        BillingLine copy = ordersApi.billingView(target).lines().getFirst();
        assertThat(copy.unitPrice().amount()).isEqualByComparingTo("0.00");
        assertThat(copy.discountLabel()).isEqualTo("Oferta");
    }

    @Test
    void aReversalOfPartOfAnOfferedLineTakesBackTheOffer() throws Exception {
        adjust(order, line, "1", "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());

        var sources = ordersApi.discountSourceIds(shop.store(), LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1),
                PageRequest.of(0, 10)).getContent();
        DiscountFacts.DiscountLine reversal = ordersApi.discountFacts("ADJUSTMENT", sources.getLast().sourceId())
                .lines().getFirst();
        assertThat(reversal.lineId()).as("the split copy").isNotEqualTo(line);
        assertThat(reversal.reversesOrigin()).isEqualTo("OFFER");
        assertThat(ordersApi.billingView(order).lines()).extracting(billed -> billed.netQty()
                .stripTrailingZeros().toPlainString() + "@" + billed.unitPrice().amount() + "@" + billed.discountLabel())
                .containsExactlyInAnyOrder("2@0.00@Oferta", "1@12.50@null");
    }

    @Test
    void aClosedOrdersOffersAreItsOfferedBillingLinesAtTheirNetQuantity() throws Exception {
        UUID target = transferOne();
        UUID copy = ordersApi.billingView(target).lines().getFirst().lineId();
        inPos(() -> ordersApi.voidLine(new SettledVoidCommand(target, version(target), copy, BigDecimal.ONE,
                "dropped", false, operator), UUID.randomUUID()));
        assertThat(ordersApi.offerFacts(order).lines()).as("open: nothing yet").isEmpty();

        close(order);
        close(target);

        OfferFacts offers = ordersApi.offerFacts(order);
        assertThat(offers.lines()).singleElement().satisfies(offered -> {
            assertThat(offered.lineId()).isEqualTo(line);
            assertThat(offered.netQuantity()).isEqualByComparingTo("2");
            assertThat(offered.consumedLineId()).isEqualTo(line);
            assertThat(offered.consumedLineQuantity()).isEqualByComparingTo("3");
            assertThat(offered.approverEmployeeId()).isEqualTo(manager.id());
        });
        assertThat(ordersApi.offerFacts(target).lines()).as("the voided copy bills nothing").isEmpty();
        assertThat(ordersApi.closedOrderIds(shop.store(), offers.businessDay(), offers.businessDay(),
                PageRequest.of(0, 10)).getContent()).containsExactlyInAnyOrder(order, target);
    }

    @Test
    void whatTheAncestorDidAfterTheCopyIsNotTheCopys() throws Exception {
        adjust(other, discounted, null, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        UUID target = transferOne(other, discounted);
        adjust(other, discounted, null, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, manager)
                .andExpect(status().isOk());

        BillingLine copy = ordersApi.billingView(target).lines().getFirst();
        assertThat(copy.unitPrice().amount()).isEqualByComparingTo("11.25");
        assertThat(copy.discountLabel()).as("offered after it moved").isEqualTo("Desconto 10%");
    }

    @Test
    void theCopyOfAPartlyReversedDiscountIsAPlainDiscount() throws Exception {
        adjust(other, discounted, null, "\"kind\": \"DISCOUNT\", \"percent\": 20", Permission.ORDER_DISCOUNT,
                manager).andExpect(status().isOk());
        adjust(other, discounted, null, "\"kind\": \"REVERSAL\", \"unitPrice\": 11.25", Permission.ORDER_DISCOUNT,
                null).andExpect(status().isOk());
        UUID target = transferOne(other, discounted);

        BillingLine copy = ordersApi.billingView(target).lines().getFirst();
        assertThat(copy.unitPrice().amount()).isEqualByComparingTo("11.25");
        assertThat(copy.discountLabel()).as("not the 20 % it no longer sells at").isEqualTo("Desconto");
        assertThat(ordersApi.billingView(other).lines().getFirst().discountLabel()).isEqualTo("Desconto");
    }

    @Test
    void anOfferReversedPartWayIsAPlainDiscountAndNoOffer() throws Exception {
        adjust(order, line, null, "\"kind\": \"REVERSAL\", \"unitPrice\": 6.25", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());

        BillingLine billed = ordersApi.billingView(order).lines().getFirst();
        assertThat(billed.unitPrice().amount()).isEqualByComparingTo("6.25");
        assertThat(billed.discountLabel()).isEqualTo("Desconto");
        close(order);
        assertThat(ordersApi.offerFacts(order).lines()).isEmpty();
    }

    @Test
    void aReversalTakesBackWhatCameBeforeItNotALaterDiscount() throws Exception {
        adjust(order, line, null, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        adjust(order, line, null, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());

        var sources = ordersApi.discountSourceIds(shop.store(), LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1),
                PageRequest.of(0, 10)).getContent();
        assertThat(sources.stream().map(source -> ordersApi.discountFacts(source.sourceType(), source.sourceId())
                        .lines().getFirst()).filter(fact -> fact.lineId().equals(line))
                .map(fact -> fact.origin() + ">" + fact.reversesOrigin()).toList())
                .containsExactly("OFFER>null", "MANUAL>OFFER", "MANUAL>null");
    }

    private UUID transferOne() throws Exception {
        return transferOne(order, line);
    }

    private UUID transferOne(UUID source, UUID from) throws Exception {
        UUID target = fixtures.openedAt(shop, fixtures.newTable(shop, "T" + UUID.randomUUID().toString()
                .substring(0, 5)), operator, terminal);
        fixtures.transfer(shop, source, version(source), target, version(target), Map.of(from, "1"), false,
                operator, terminal, UUID.randomUUID().toString()).andExpect(status().isCreated());
        return target;
    }

    private void close(UUID orderId) {
        inPos(() -> {
            ordersApi.close(orderId, version(orderId), operator);
            return null;
        });
    }

    private ResultActions adjust(UUID orderId, UUID lineId, String quantity, String values, Permission permission,
                                 Employee approver) throws Exception {
        String target = quantity == null ? "{\"lineId\": \"%s\"}".formatted(lineId)
                : "{\"lineId\": \"%s\", \"quantity\": %s}".formatted(lineId, quantity);
        return fixtures.discount(shop, orderId, """
                {"orderVersion": %d, "lines": [%s], %s, "reason": "regular"}"""
                .formatted(version(orderId), target, values), operator, terminal, permission, approver);
    }

    private long version(UUID orderId) {
        return ordersApi.billingView(orderId).version();
    }

    private <T> T inPos(Supplier<T> action) {
        return OrdersActors.runAsPos(shop.store(), operator, terminal,
                () -> new TransactionTemplate(transactionManager).execute(status -> action.get()));
    }
}
