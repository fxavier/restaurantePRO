package com.xavier.backend.orders.delivery;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The POS dispatch screen's courier choice: what a dispatch accepts, the store's active employees holding
 * DELIVERY_COURIER there (S50, Q4).
 */
@OrdersModuleTest
class CourierListHttpTest {

    static final String COURIERS = "/api/v1/pos/couriers";

    @Autowired
    MockMvc mvc;

    IdentityFixtures identity;
    UUID store;

    @BeforeEach
    void setUp() throws Exception {
        identity = new IdentityFixtures(mvc);
        store = identity.store();
    }

    @Test
    void theCouriersAreTheStoresActiveEmployeesHoldingTheCourierPermissionByName() throws Exception {
        UUID driver = identity.role(Permission.DELIVERY_COURIER);
        // created first but named last, so creation order and name order disagree
        IdentityFixtures.Employee zita = identity.employee(OrdersFixtures.PIN, store, driver);
        IdentityFixtures.Employee bruno = identity.employee(OrdersFixtures.PIN, store, driver);
        IdentityFixtures.Employee gone = identity.employee(OrdersFixtures.PIN, store, driver);
        IdentityFixtures.Employee waiter = identity.employee(OrdersFixtures.PIN, store, IdentityFixtures.WAITER);
        IdentityFixtures.Employee elsewhere = identity.employee(OrdersFixtures.PIN, identity.store(), driver);
        edit(zita, "Zita", true);
        edit(bruno, "Bruno", true);
        edit(gone, "Ana", false);

        mvc.perform(get(COURIERS).with(dispatcher(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", contains(bruno.id().toString(), zita.id().toString())))
                .andExpect(jsonPath("$[*].name", contains("Bruno", "Zita")))
                .andExpect(jsonPath("$[*].id", not(hasItem(gone.id().toString()))))
                .andExpect(jsonPath("$[*].id", not(hasItem(waiter.id().toString()))))
                .andExpect(jsonPath("$[*].id", not(hasItem(elsewhere.id().toString()))))
                .andExpect(jsonPath("$[0].code").isString())
                .andExpect(jsonPath("$[0].pin").doesNotExist());
    }

    private void edit(IdentityFixtures.Employee employee, String name, boolean active) throws Exception {
        mvc.perform(put("/api/v1/backoffice/employees/" + employee.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "%s", "active": %s, "version": 0}""".formatted(name, active)))
                .andExpect(status().isOk());
    }

    @Test
    void listingNeedsAPosSessionThatMayDispatch() throws Exception {
        mvc.perform(get(COURIERS).with(OrdersActors.pos(store, UUID.randomUUID(), UUID.randomUUID(),
                        Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(COURIERS).with(OrdersActors.backOffice(Permission.DELIVERY_OPERATE)))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor dispatcher(UUID store) {
        return OrdersActors.pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.DELIVERY_OPERATE);
    }
}
