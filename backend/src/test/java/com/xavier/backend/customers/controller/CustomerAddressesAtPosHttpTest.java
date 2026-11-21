package com.xavier.backend.customers.controller;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.POS;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.pos;
import static com.xavier.backend.customers.CustomersFixtures.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The POS delivery modal reads a customer's saved addresses (customers are company-wide, P38). */
@CustomersModuleTest
class CustomerAddressesAtPosHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomersFixtures fixtures;

    CustomersFixtures.Pos waiter;
    UUID customer;

    @BeforeEach
    void setUp() throws Exception {
        waiter = pos(fixtures.newStore());
        customer = fixtures.newCustomer(waiter, "Delivery " + token(), null, null);
    }

    @Test
    void thePosReadsTheCustomersSavedAddresses() throws Exception {
        mvc.perform(idem(post(BO + "/" + customer + "/addresses")).with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind": "DELIVERY", "line1": "Av. Marginal 12", "city": "Maputo", "country": "MZ",
                                 "version": 0}"""))
                .andExpect(status().isCreated());

        mvc.perform(get(POS + "/" + customer + "/addresses").with(waiter.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("DELIVERY"))
                .andExpect(jsonPath("$[0].line1").value("Av. Marginal 12"));
    }

    @Test
    void readingNeedsAPosSessionWithCustomerView() throws Exception {
        mvc.perform(get(POS + "/" + customer + "/addresses").with(waiter.with(Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS + "/" + customer + "/addresses").with(account(null, Permission.CUSTOMER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS + "/" + UUID.randomUUID() + "/addresses").with(waiter.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isNotFound());
    }
}
