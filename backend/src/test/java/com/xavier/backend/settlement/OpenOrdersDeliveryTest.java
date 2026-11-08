package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.DeliverySale;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D129, A51 (settlement half): the cashier's open orders and an order's settlement show how it is served, its customer
 * and, for a DELIVERY order, its delivery (status, courier, promise, zone), so the cashier sees what each courier
 * still owes. Other modes show no delivery.
 */
@SettlementModuleTest
class OpenOrdersDeliveryTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Test
    void theOpenOrdersShowADeliveryOutWithItsCourier() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();
        delivery.deliveries().dispatch(delivery.shop(), sale.order(), 0, delivery.shop().courier())
                .andExpect(status().isOk());

        JsonNode open = openOrder(sale, sale.order());

        assertThat(open.get("mode").asString()).isEqualTo("DELIVERY");
        assertThat(open.get("customerId").asString()).isEqualTo(delivery.shop().customer().toString());
        assertThat(open.get("delivery").get("status").asString()).isEqualTo("OUT_FOR_DELIVERY");
        assertThat(open.get("delivery").get("courierEmployeeId").asString())
                .isEqualTo(delivery.shop().courier().toString());
        assertThat(open.get("delivery").has("promisedAt")).isTrue();
        assertThat(open.get("delivery").get("zoneCode").isNull()).isFalse();
        assertThat(open.get("due").get("amount").decimalValue()).isEqualByComparingTo("15.50"); // fee 3.00 + 12.50
    }

    @Test
    void theOrdersSettlementShowsItsModeCustomerAndDelivery() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();

        fixtures.settlement(sale.cashier(), sale.order()).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DELIVERY"))
                .andExpect(jsonPath("$.customerId").value(delivery.shop().customer().toString()))
                .andExpect(jsonPath("$.delivery.status").value("PENDING"));
    }

    @Test
    void aTableOrderShowsItsModeAndNoDelivery() throws Exception {
        Sale sale = fixtures.sale("1");

        JsonNode open = openOrder(sale, sale.order());

        assertThat(open.get("mode").asString()).isEqualTo("DINE_IN");
        assertThat(open.get("customerId").isNull()).isTrue();
        assertThat(open.get("delivery").isNull()).isTrue();
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.mode").value("DINE_IN"))
                .andExpect(jsonPath("$.delivery").isEmpty());
    }

    private JsonNode openOrder(Sale sale, UUID order) throws Exception {
        JsonNode list = SettlementFixtures.json(mvc.perform(get("/api/v1/pos/settlement/open-orders")
                .with(sale.cashier().with(SettlementFixtures.CASHIER))).andExpect(status().isOk()));
        for (JsonNode entry : list) {
            if (entry.get("orderId").asString().equals(order.toString())) {
                return entry;
            }
        }
        throw new AssertionError("order " + order + " is not among the open orders");
    }
}
