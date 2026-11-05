package com.xavier.backend.customers.controller;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.POS;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.digits;
import static com.xavier.backend.customers.CustomersFixtures.id;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.pos;
import static com.xavier.backend.customers.CustomersFixtures.token;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Customer maintenance at the BackOffice (D72): {@code CUSTOMER_MANAGE} / {@code CUSTOMER_VIEW} in any store. */
@CustomersModuleTest
class CustomerBackOfficeHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomersFixtures fixtures;

    @Test
    void aManagerCreatesACustomerAndEditsItWithItsVersion() throws Exception {
        UUID customer = create("Joana " + token());

        mvc.perform(put(BO + "/" + customer).with(csrf()).with(account(UUID.randomUUID(), Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("Joana M.", false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Joana M."))
                .andExpect(jsonPath("$.email").value("joana@example.com"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(put(BO + "/" + customer).with(csrf()).with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("Stale", true, 0)))
                .andExpect(status().isConflict());
        mvc.perform(get(BO + "/" + customer).with(account(UUID.randomUUID(), Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Joana M."));
    }

    @Test
    void writesNeedCustomerManageAndACreateNeedsAKey() throws Exception {
        mvc.perform(idem(post(BO)).with(account(null, Permission.CUSTOMER_VIEW))
                        .contentType(MediaType.APPLICATION_JSON).content(CustomersFixtures.customerBody("X", null, null)))
                .andExpect(status().isForbidden());
        mvc.perform(post(BO).with(csrf()).with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(CustomersFixtures.customerBody("X", null, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(idem(post(BO)).with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(CustomersFixtures.customerBody(" ", "12", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        mvc.perform(put(BO + "/" + UUID.randomUUID()).with(csrf()).with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(update("Nobody", true, 0)))
                .andExpect(status().isNotFound());
    }

    @Test
    void phonesAreStoredAsDigitsOncePerCustomerAndOneIsPrimary() throws Exception {
        UUID customer = create("Phone " + token());
        String first = "84" + digits(7);
        String second = "82" + digits(7);

        addPhone(customer, "+258 " + first, false).andExpect(status().isCreated())
                .andExpect(jsonPath("$.digits").value("258" + first)).andExpect(jsonPath("$.primary").value(true));
        UUID secondId = id(addPhone(customer, second, true).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        addPhone(customer, "258-" + first, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_EXISTS"));

        mvc.perform(get(BO + "/" + customer).with(account(null, Permission.CUSTOMER_VIEW)))
                .andExpect(jsonPath("$.phones.length()").value(2))
                .andExpect(jsonPath("$.phones[0].digits").value(second))
                .andExpect(jsonPath("$.phones[0].primary").value(true))
                .andExpect(jsonPath("$.phones[1].primary").value(false));

        mvc.perform(delete(BO + "/" + customer + "/phones/" + secondId).with(csrf())
                        .with(account(null, Permission.CUSTOMER_MANAGE)))
                .andExpect(status().isNoContent());
        mvc.perform(get(POS + "/" + customer).with(pos(fixtures.newStore()).with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.phones.length()").value(1));
    }

    @Test
    void addressesAreAddedAndEditedWithTheirVersion() throws Exception {
        UUID customer = create("Address " + token());

        String body = mvc.perform(idem(post(BO + "/" + customer + "/addresses"))
                        .with(account(null, Permission.CUSTOMER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(address("BILLING", "Av. Julius Nyerere 100", 0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("BILLING")).andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID address = id(body);

        mvc.perform(put(BO + "/" + customer + "/addresses/" + address).with(csrf())
                        .with(account(null, Permission.CUSTOMER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(address("BILLING", "Av. 24 de Julho 7", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.line1").value("Av. 24 de Julho 7"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(BO + "/" + customer + "/addresses/" + address).with(csrf())
                        .with(account(null, Permission.CUSTOMER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(address("BILLING", "Stale", 0)))
                .andExpect(status().isConflict());
        mvc.perform(idem(post(BO + "/" + customer + "/addresses"))
                        .with(account(null, Permission.CUSTOMER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(address("HOME", "x", 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BO + "/" + UUID.randomUUID() + "/addresses/" + address).with(csrf())
                        .with(account(null, Permission.CUSTOMER_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content(address("BILLING", "Elsewhere", 1)))
                .andExpect(status().isNotFound());
    }

    private UUID create(String name) throws Exception {
        return id(mvc.perform(idem(post(BO)).with(account(UUID.randomUUID(), Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "email": "joana@example.com", "notes": "prefers table 4"}"""
                                .formatted(name)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions addPhone(UUID customer, String number,
                                                                        boolean primary) throws Exception {
        return mvc.perform(idem(post(BO + "/" + customer + "/phones")).with(account(null, Permission.CUSTOMER_MANAGE))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"number": "%s", "label": "mobile", "primary": %s}""".formatted(number, primary)));
    }

    private static String update(String name, boolean active, long version) {
        return """
                {"name": "%s", "taxId": null, "email": "joana@example.com", "notes": null, "active": %s,
                 "version": %d}""".formatted(name, active, version);
    }

    private static String address(String kind, String line1, long version) {
        return """
                {"kind": "%s", "line1": "%s", "city": "Maputo", "country": "MZ", "version": %d}"""
                .formatted(kind, line1, version);
    }
}
