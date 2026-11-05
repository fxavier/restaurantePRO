package com.xavier.backend.customers.controller;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.POS;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.digits;
import static com.xavier.backend.customers.CustomersFixtures.pos;
import static com.xavier.backend.customers.CustomersFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.customers.CustomersFixtures.Pos;
import com.xavier.backend.customers.CustomersModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Search returns candidates, never a selection (spec §5 "nunca selecionar automaticamente", D72, A31): by phone
 * suffix, by a part of the name (accents and case ignored) and by exact tax id, at most 20, always a list.
 */
@CustomersModuleTest
class CustomerSearchTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CustomersFixtures fixtures;

    Pos cashier;

    @BeforeEach
    void cashierOfANewStore() throws Exception {
        cashier = pos(fixtures.newStore());
    }

    @Test
    void aPhoneSuffixReturnsEveryCustomerWhosePhoneEndsWithIt() throws Exception {
        String tail = digits(7);
        UUID first = fixtures.newCustomer(cashier, "Ana " + token(), "+258 84 " + tail, null);
        UUID second = fixtures.newCustomer(cashier, "Rui " + token(), "82" + tail, null);
        fixtures.newCustomer(cashier, "Eva " + token(), "84" + digits(7), null);

        assertThat(candidateIds(tail)).containsExactlyInAnyOrder(first, second);
        assertThat(candidateIds(tail.substring(0, 3) + "-" + tail.substring(3))).hasSize(2);
    }

    @Test
    void aSingleMatchIsStillACandidateList() throws Exception {
        String tail = digits(8);
        UUID only = fixtures.newCustomer(cashier, "Lia " + token(), "84" + tail, null);

        mvc.perform(get(POS).param("q", tail).with(cashier.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(only.toString()))
                .andExpect(jsonPath("$[0].phones[0]").value("84" + tail));
    }

    @Test
    void aPartOfTheNameMatchesIgnoringAccentsAndCase() throws Exception {
        String token = token();
        UUID celia = fixtures.newCustomer(cashier, "Célia " + token + " Mabunda", null, null);

        assertThat(candidateIds(token.substring(2, 7))).containsExactly(celia);
        assertThat(candidateIds("CELIA " + token.toUpperCase())).containsExactly(celia);
        assertThat(candidateIds("élia " + token)).containsExactly(celia);
    }

    @Test
    void anExactTaxIdMatchesEveryCustomerSharingIt() throws Exception {
        String taxId = "4" + digits(8);
        UUID mother = fixtures.newCustomer(cashier, "Mãe " + token(), null, taxId);
        UUID son = fixtures.newCustomer(cashier, "Filho " + token(), null, taxId);

        assertThat(candidateIds(taxId)).containsExactlyInAnyOrder(mother, son);
        assertThat(candidateIds("PT" + taxId)).isEmpty();
    }

    @Test
    void atMostTwentyCandidatesAreReturned() throws Exception {
        String token = token();
        for (int i = 0; i < 21; i++) {
            fixtures.newCustomer(cashier, "Many " + token + " " + i, null, null);
        }

        assertThat(candidateIds(token)).hasSize(20);
    }

    @Test
    void aSearchNeedsFourDigitsOrThreeCharacters() throws Exception {
        for (String tooShort : List.of("123", "ab", "  ", "+1 2")) {
            mvc.perform(get(POS).param("q", tooShort).with(cashier.with(Permission.CUSTOMER_VIEW)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(POS).with(cashier.with(Permission.CUSTOMER_VIEW))).andExpect(status().isBadRequest());
    }

    @Test
    void searchingNeedsCustomerViewAtThePosOrInAnyStoreAtTheBackOffice() throws Exception {
        mvc.perform(get(POS).param("q", "abcd").with(cashier.with(Permission.CUSTOMER_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS).param("q", "abcd").with(account(cashier.storeId(), Permission.CUSTOMER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO).param("q", "abcd").with(account(UUID.randomUUID(), Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk());
        mvc.perform(get(BO).param("q", "abcd").with(account(null, Permission.CUSTOMER_MANAGE)))
                .andExpect(status().isForbidden());
    }

    private List<UUID> candidateIds(String q) throws Exception {
        String body = mvc.perform(get(POS).param("q", q).with(cashier.with(Permission.CUSTOMER_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return CustomersFixtures.json(body).valueStream().map(c -> UUID.fromString(c.get("id").asString())).toList();
    }
}
