package com.xavier.backend.orders.delivery;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
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

/** The POS dispatch screen's courier choice: what a dispatch accepts, the store's active employees (S50). */
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
    void theCouriersAreTheStoresActiveEmployees() throws Exception {
        IdentityFixtures.Employee ana = identity.employee(OrdersFixtures.PIN, store, IdentityFixtures.WAITER);
        IdentityFixtures.Employee rui = identity.employee(OrdersFixtures.PIN, store, IdentityFixtures.WAITER);
        IdentityFixtures.Employee gone = identity.employee(OrdersFixtures.PIN, store, IdentityFixtures.WAITER);
        IdentityFixtures.Employee elsewhere = identity.employee(OrdersFixtures.PIN, identity.store(),
                IdentityFixtures.WAITER);
        mvc.perform(put("/api/v1/backoffice/employees/" + gone.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"name": "Ana", "active": false, "version": 0}"""))
                .andExpect(status().isOk());

        mvc.perform(get(COURIERS).with(dispatcher(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].id", hasItems(ana.id().toString(), rui.id().toString())))
                .andExpect(jsonPath("$[*].id", not(hasItem(gone.id().toString()))))
                .andExpect(jsonPath("$[*].id", not(hasItem(elsewhere.id().toString()))))
                .andExpect(jsonPath("$[0].code").isString())
                .andExpect(jsonPath("$[0].name").value("Ana"));
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
