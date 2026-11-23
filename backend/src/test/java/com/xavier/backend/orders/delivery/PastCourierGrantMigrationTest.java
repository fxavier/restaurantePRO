package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * V6033 (Q4, no capability loss): a role an existing delivery's courier holds in that delivery's store gets
 * DELIVERY_COURIER, whatever it is; the courier's roles in other stores and other roles do not. The migration ran
 * before this data existed, so the test runs its script again as the owner (it is idempotent).
 */
@OrdersModuleTest
class PastCourierGrantMigrationTest {

    static final String SCRIPT = "db/migration/orders/V6033__orders_grant_courier_to_past_couriers.sql";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Test
    void aRoleAPastCourierHoldsInTheDeliverysStoreMayCarryDeliveries() throws Exception {
        DeliveryFixtures deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        DeliveryShop d = deliveries.shop();
        IdentityFixtures identity = new IdentityFixtures(mvc);
        UUID order = deliveries.confirmedDelivery(d, UUID.randomUUID(), UUID.randomUUID());
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        JdbcClient owner = OwnerDatabase.shared().jdbc();
        UUID courierRole = owner.sql("SELECT role_id FROM identity.employee_store_assignment "
                        + "WHERE employee_id = ? AND store_id = ?")
                .param(d.courier()).param(d.shop().store()).query(UUID.class).single();
        // before V3018 a courier's role needed no permission: the courier's role as an old tenant has it
        owner.sql("DELETE FROM identity.role_permission WHERE role_id = ?").param(courierRole).update();
        UUID roleElsewhere = identity.role(Permission.ORDER_VIEW);
        owner.sql("INSERT INTO identity.employee_store_assignment (id, tenant_id, employee_id, store_id, role_id, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, now())")
                .param(UUID.randomUUID()).param(TenantTestSupport.BOOTSTRAP).param(d.courier())
                .param(identity.store()).param(roleElsewhere).update();
        UUID unrelatedRole = identity.role(Permission.ORDER_VIEW);
        identity.employee(OrdersFixtures.PIN, d.shop().store(), unrelatedRole);

        owner.sql(new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8)).update();
        owner.sql(new ClassPathResource(SCRIPT).getContentAsString(StandardCharsets.UTF_8)).update();

        assertThat(permissionsOf(owner, courierRole)).containsExactly("DELIVERY_COURIER");
        assertThat(permissionsOf(owner, roleElsewhere)).containsExactly("ORDER_VIEW");
        assertThat(permissionsOf(owner, unrelatedRole)).containsExactly("ORDER_VIEW");
    }

    private static java.util.List<String> permissionsOf(JdbcClient owner, UUID role) {
        return owner.sql("SELECT permission FROM identity.role_permission WHERE role_id = ?").param(role)
                .query(String.class).list();
    }
}
