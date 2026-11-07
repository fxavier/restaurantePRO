package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D127, D128: a DELIVERY order opens with its customer, an address snapshot and the zone's FEE item as an automatic
 * draft, priced and taxed at confirmation from that item's own release snapshot; every refusal is decided before
 * anything is written.
 */
@OrdersModuleTest
class DeliveryFeeLineTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    DeliveryFixtures deliveries;
    DeliveryShop d;

    @BeforeEach
    void aDeliveryShop() throws Exception {
        deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        d = deliveries.shop();
    }

    @Test
    void openingSnapshotsCustomerAddressAndPhoneAndAddsTheZonesFeeAsAnAutomaticDraft() throws Exception {
        JsonNode order = deliveries.opened(d, employee, terminal);
        UUID id = OrdersFixtures.id(order);

        assertThat(order.get("mode").asString()).isEqualTo("DELIVERY");
        assertThat(order.get("customerId").asString()).isEqualTo(d.customer().toString());
        assertThat(order.get("tableId").isNull()).isTrue();
        assertThat(DeliveryFixtures.list(order.get("lines"))).singleElement().satisfies(line -> {
            assertThat(line.get("itemId").asString()).isEqualTo(d.feeItem().toString());
            assertThat(line.get("automatic").asBoolean()).isTrue();
            assertThat(line.get("status").asString()).isEqualTo("DRAFT");
            assertThat(line.get("quantity").decimalValue()).isEqualByComparingTo("1");
        });
        Map<String, Object> row = jdbc.sql("SELECT * FROM orders.delivery WHERE order_id = ?").param(id).query()
                .singleRow();
        assertThat(row).containsEntry("status", "PENDING").containsEntry("phone_digits", d.phone())
                .containsEntry("address_line1", "Rua da Entrega 7").containsEntry("city", "Maputo")
                .containsEntry("address_notes", "portão azul").containsEntry("customer_id", d.customer())
                .containsEntry("zone_id", d.zone());
        assertThat(deliveries.history(id)).containsExactly("->PENDING");
        assertThat(fixtures.audits("DELIVERY_OPENED", id)).isOne();
    }

    @Test
    void theConfirmedFeeIsTaxedAndPricedFromItsOwnFeeItem() throws Exception {
        UUID id = deliveries.confirmedDelivery(d, employee, terminal);

        JsonNode fee = DeliveryFixtures.list(fixtures.order(d.shop(), id).get("lines")).stream()
                .filter(line -> line.get("itemId").asString().equals(d.feeItem().toString())).findFirst()
                .orElseThrow();
        assertThat(fee.get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(fee.get("kind").asString()).isEqualTo("FEE");
        assertThat(fee.get("taxRatePercent").decimalValue()).isEqualByComparingTo("5");
        assertThat(fee.get("unitPrice").get("amount").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(fee.get("gross").get("amount").decimalValue()).isEqualByComparingTo("3.00");
    }

    @Test
    void aTypedAddressTakesTheNotesAndACustomerWithoutAPhoneHasNone() throws Exception {
        UUID silent = customers.newCustomer(CustomersFixtures.pos(d.shop().store()),
                "Mudo " + CustomersFixtures.token(), null, null);

        UUID id = OrdersFixtures.id(OrdersFixtures.json(deliveries.open(d.shop(), """
                        {"customerId": "%s", "address": {"line1": "Av. 24 de Julho 100", "notes": "old"},
                         "zoneId": "%s", "notes": "ring twice", "promisedAt": "2027-03-01T13:00:00Z"}"""
                        .formatted(silent, d.zone()), employee, terminal, Permission.ORDER_EDIT,
                Permission.DELIVERY_OPERATE).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()));

        Map<String, Object> row = jdbc.sql("SELECT * FROM orders.delivery WHERE order_id = ?").param(id).query()
                .singleRow();
        assertThat(row.get("phone_digits")).isNull();
        assertThat(row).containsEntry("address_line1", "Av. 24 de Julho 100")
                .containsEntry("address_notes", "ring twice");
        assertThat(row.get("promised_at")).isNotNull();
    }

    @Test
    void openingIsRefusedWithoutCustomerZoneOrPublishedFeeAndWritesNothing() throws Exception {
        UUID foreignZone = deliveries.zone(fixtures.shop(), "F" + CustomersFixtures.token().substring(0, 6),
                d.feeItem());
        UUID unpublishedFee = deliveries.zone(d.shop(), "U" + CustomersFixtures.token().substring(0, 6),
                deliveries.feeItem(d.shop(), "5", "2.00"));
        UUID notAFee = deliveries.zone(d.shop(), "N" + CustomersFixtures.token().substring(0, 6), d.shop().item());
        UUID inactive = deliveries.zone(d.shop(), "I" + CustomersFixtures.token().substring(0, 6), d.feeItem());
        deliveries.deactivateZone(inactive);
        long before = orderCount();

        refused("""
                {"addressId": "%s", "zoneId": "%s"}""".formatted(d.address(), d.zone()), "CUSTOMER_REQUIRED");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(DeliveryFixtures.POS
                                + "/orders")
                        .with(com.xavier.backend.orders.OrdersActors.pos(d.shop().store(), employee, terminal,
                                Permission.ORDER_EDIT, Permission.DELIVERY_OPERATE))
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"DELIVERY\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CUSTOMER_REQUIRED"));
        refused(zone(inactive), "ZONE_INACTIVE");
        refused(zone(foreignZone), "ZONE_OTHER_STORE");
        refused(zone(unpublishedFee), "FEE_ITEM_NOT_PUBLISHED");
        refused(zone(notAFee), "FEE_ITEM_NOT_PUBLISHED");
        deliveries.open(d.shop(), """
                        {"customerId": "%s", "addressId": "%s", "address": {"line1": "x"}, "zoneId": "%s"}"""
                        .formatted(d.customer(), d.address(), d.zone()), employee, terminal, Permission.ORDER_EDIT,
                Permission.DELIVERY_OPERATE).andExpect(status().isBadRequest());
        deliveries.open(d.shop(), DeliveryFixtures.delivery(d), employee, terminal, Permission.ORDER_EDIT)
                .andExpect(status().isForbidden());
        deliveries.open(d.shop(), """
                        {"customerId": "%s", "addressId": "%s", "zoneId": "%s"}""".formatted(d.customer(),
                        UUID.randomUUID(), d.zone()), employee, terminal, Permission.ORDER_EDIT,
                Permission.DELIVERY_OPERATE).andExpect(status().isNotFound());

        assertThat(orderCount()).isEqualTo(before);
    }

    String zone(UUID zone) {
        return """
                {"customerId": "%s", "addressId": "%s", "zoneId": "%s"}""".formatted(d.customer(), d.address(), zone);
    }

    void refused(String delivery, String code) throws Exception {
        deliveries.open(d.shop(), delivery, employee, terminal, Permission.ORDER_EDIT, Permission.DELIVERY_OPERATE)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value(code));
    }

    long orderCount() {
        return fixtures.count("SELECT count(*) FROM orders.order_header WHERE store_id = ?", d.shop().store());
    }
}
