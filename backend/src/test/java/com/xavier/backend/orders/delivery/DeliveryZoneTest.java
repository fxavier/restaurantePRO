package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D128: a store's zones have unique codes, are managed only with the permission in that store, and audited. */
@OrdersModuleTest
class DeliveryZoneTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Test
    void aCodeIsUniquePerStoreTheScopeIsTheZonesStoreAndThePosSeesOnlyActiveZones() throws Exception {
        DeliveryFixtures deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        Shop shop = fixtures.shop();
        UUID otherStore = new com.xavier.backend.catalog.CatalogFixtures(mvc).store();
        String code = "C" + CustomersFixtures.token().substring(0, 6);
        UUID zone = deliveries.zone(shop, code, shop.item());
        UUID retired = deliveries.zone(shop, code + "X", shop.item());
        deliveries.deactivateZone(retired);

        deliveries.createZone(shop.store(), code, shop.item()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_ZONE_CODE_TAKEN"));
        deliveries.createZone(otherStore, code, shop.item()).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/backoffice/delivery-zones/" + zone)
                        .with(OrdersActors.pos(otherStore, UUID.randomUUID(), UUID.randomUUID(),
                                Permission.DELIVERY_ZONE_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/delivery-zones/" + UUID.randomUUID())
                        .with(OrdersActors.backOffice(Permission.DELIVERY_ZONE_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pos/delivery-zones")
                        .with(OrdersActors.pos(shop.store(), UUID.randomUUID(), UUID.randomUUID(),
                                Permission.DELIVERY_OPERATE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(zone.toString()));
        assertThat(fixtures.count("SELECT count(*) FROM audit.audit_event WHERE entity_id = ? AND action IN "
                + "('DELIVERY_ZONE_CREATED', 'DELIVERY_ZONE_CHANGED')", retired.toString())).isEqualTo(2);
    }
}
