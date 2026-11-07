package com.xavier.backend.orders.discounts;

import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.OrdersPromotions;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * D105, D106 (P44): a discount after confirmation is a fact that lowers the line's effective price, measured
 * cumulatively against the list price: within the store's free limit (10 %) the operator alone, up to its maximum
 * (50 %) with an approver holding ORDER_DISCOUNT_APPROVE who is not the operator, never above it. An offer always
 * needs the approver; nothing here may raise the price, and nothing leaves the bill while a payment is under way.
 * A40 (orders route).
 */
@OrdersModuleTest
class DiscountLimitTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersPromotions promotions;

    @Autowired
    OrdersApi ordersApi;

    final UUID operator = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;
    UUID line;
    Employee manager;

    @BeforeEach
    void aConfirmedLineOfTwoAtTwelveFifty() throws Exception {
        shop = fixtures.shop();
        promotions.limits(shop.store(), "10", "50");
        long promotionVersion = promotions.publish(shop.store());
        order = id(fixtures.openedOrder(shop, operator, terminal));
        line = id(fixtures.draft(shop, order, "2", operator, terminal));
        fixtures.confirm(shop, order, """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"], "promotionVersion": %d}"""
                .formatted(shop.catalogVersion(), line, promotionVersion), operator, terminal)
                .andExpect(status().isCreated());
        manager = fixtures.employee(shop, IdentityFixtures.MANAGER);
    }

    @Test
    void withinTheFreeLimitTheOperatorDiscountsAloneAndTheBillFollows() throws Exception {
        String adjusted = discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(11.25))
                .andExpect(jsonPath("$.lines[0].listUnitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].origin").value("MANUAL"))
                .andExpect(jsonPath("$.lines[0].adjustments[0].kind").value("DISCOUNT"))
                .andExpect(jsonPath("$.lines[0].adjustments[0].approverEmployeeId").doesNotExist())
                .andExpect(jsonPath("$.due.amount").value(22.50))
                .andReturn().getResponse().getContentAsString();

        assertThat(ordersApi.billingView(order).lines().getFirst().discountLabel()).isEqualTo("Desconto 10%");
        UUID adjustment = UUID.fromString(OrdersFixtures.json(adjusted).get("lines").get(0).get("adjustments").get(0)
                .get("id").asString());
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE action = 'ORDER_LINE_PRICE_ADJUSTED' "
                + "AND entity_id = ? AND actor_id = ? AND reason = 'regular' AND approver_id IS NULL",
                adjustment.toString(), operator)).isOne();
    }

    @Test
    void aboveTheFreeLimitAnApproverIsNeededAndRecorded() throws Exception {
        discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 20", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isForbidden());

        discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 20", Permission.ORDER_DISCOUNT, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(10.00))
                .andExpect(jsonPath("$.lines[0].adjustments[0].approverEmployeeId").value(manager.id().toString()));
    }

    @Test
    void theLimitIsCumulativeAgainstTheListPrice() throws Exception {
        discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());

        discount(2, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null) // 19 % of list
                .andExpect(status().isForbidden());
        discount(2, "\"kind\": \"DISCOUNT\", \"unitPrice\": 6.00", Permission.ORDER_DISCOUNT, manager) // 52 %
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DISCOUNT_LIMIT_EXCEEDED"));
    }

    @Test
    void anOfferIsPriceZeroAndAlwaysApproved() throws Exception {
        discount(1, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, null).andExpect(status().isForbidden());
        discount(1, "\"kind\": \"OFFER\"", Permission.ORDER_DISCOUNT, manager).andExpect(status().isForbidden());

        discount(1, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, manager)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(0.00))
                .andExpect(jsonPath("$.lines[0].origin").value("OFFER"))
                .andExpect(jsonPath("$.due.amount").value(0.00));
    }

    @Test
    void aReversalNamesTheOriginOfWhatItReverses() throws Exception {
        discount(1, "\"kind\": \"OFFER\"", Permission.ORDER_OFFER, manager).andExpect(status().isOk());
        discount(2, "\"kind\": \"REVERSAL\", \"unitPrice\": 5.00", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        discount(3, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, null).andExpect(status().isOk());
        discount(4, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());
        discount(5, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, null).andExpect(status().isOk());

        var sources = ordersApi.discountSourceIds(shop.store(), java.time.LocalDate.of(2000, 1, 1),
                java.time.LocalDate.of(2100, 1, 1), org.springframework.data.domain.PageRequest.of(0, 10))
                .getContent();
        assertThat(sources).hasSize(5);
        assertThat(sources.stream().map(source -> ordersApi.discountFacts(source.sourceType(), source.sourceId())
                .lines().getFirst()).map(line -> line.origin() + ">" + line.reversesOrigin()).toList())
                .as("a reversal after a reversal still takes back the offer")
                .containsExactly("OFFER>null", "MANUAL>OFFER", "MANUAL>OFFER", "MANUAL>null", "MANUAL>MANUAL");
    }

    @Test
    void theApproverMustNotBeTheOperator() throws Exception {
        fixtures.discount(shop, order, body(1, "\"kind\": \"DISCOUNT\", \"percent\": 20"), manager.id(), terminal,
                        Permission.ORDER_DISCOUNT, manager)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
    }

    @Test
    void aDiscountNeverRaisesThePriceAndAReversalRestoresAtMostTheListPrice() throws Exception {
        discount(1, "\"kind\": \"DISCOUNT\", \"unitPrice\": 13.00", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRICE_INCREASE_NOT_ALLOWED"));
        discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk());

        discount(2, "\"kind\": \"REVERSAL\", \"unitPrice\": 13.00", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PRICE_INCREASE_NOT_ALLOWED"));
        discount(2, "\"kind\": \"REVERSAL\"", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.lines[0].origin").doesNotExist())
                .andExpect(jsonPath("$.lines[0].adjustments.length()").value(2));
    }

    @Test
    void nothingIsDiscountedWhileAPaymentIsUnderWay() throws Exception {
        fixtures.inPos(shop, () -> ordersApi.lockForPayment(order));

        discount(2, "\"kind\": \"DISCOUNT\", \"percent\": 10", Permission.ORDER_DISCOUNT, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IN_SETTLEMENT"));
    }

    @Test
    void aBodyWhoseValuesDoNotMatchTheKindIsInvalid() throws Exception {
        discount(1, "\"kind\": \"DISCOUNT\", \"percent\": 10, \"unitPrice\": 5.00", Permission.ORDER_DISCOUNT,
                null).andExpect(status().isBadRequest());
    }

    ResultActions discount(long orderVersion, String values, Permission permission, Employee approver)
            throws Exception {
        return fixtures.discount(shop, order, body(orderVersion, values), operator, terminal, permission, approver);
    }

    String body(long orderVersion, String values) {
        return """
                {"orderVersion": %d, "lines": [{"lineId": "%s"}], %s, "reason": "regular"}"""
                .formatted(orderVersion, line, values);
    }
}
