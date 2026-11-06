package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.BO;
import static com.xavier.backend.customers.CustomersFixtures.account;
import static com.xavier.backend.customers.CustomersFixtures.idem;
import static com.xavier.backend.customers.CustomersFixtures.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.exception.CustomerRuleException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** The buyer a credit-sale document is issued to (D65: settlement passes it to fiscal as an inline snapshot). */
@CustomersModuleTest
class CustomerSnapshotTest {

    @Autowired
    CustomersApi customers;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theSnapshotHasTheNameTaxIdAndFirstBillingAddress() throws Exception {
        String name = "Snapshot " + token();
        UUID customer = fixtures.newCustomer(CustomersFixtures.pos(fixtures.newStore()), name, null, "400123456");
        addAddress(customer, "DELIVERY", "Rua da Entrega 1");
        addAddress(customer, "BILLING", "Av. Eduardo Mondlane 55");
        addAddress(customer, "BILLING", "Second billing");

        CustomerSnapshot snapshot = customers.snapshot(customer);

        assertThat(snapshot.customerId()).isEqualTo(customer);
        assertThat(snapshot.name()).isEqualTo(name);
        assertThat(snapshot.taxId()).isEqualTo("400123456");
        assertThat(snapshot.billingAddress().line1()).isEqualTo("Av. Eduardo Mondlane 55");
    }

    @Test
    void anInactiveCustomerHasNoSnapshotAndAnUnknownOneIsNotFound() throws Exception {
        UUID customer = fixtures.newCustomer(CustomersFixtures.pos(fixtures.newStore()), "Gone " + token(), null,
                null);
        assertThat(customers.snapshot(customer).billingAddress()).isNull();
        jdbc.sql("UPDATE customers.customer SET active = false WHERE id = ?").param(customer).update();

        assertThatThrownBy(() -> customers.snapshot(customer)).isInstanceOf(CustomerRuleException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_INACTIVE");
        assertThatThrownBy(() -> customers.snapshot(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    /** D100: a delivery order snapshots one of its customer's addresses, read through the owner's API. */
    @Test
    void anAddressIsReadOnlyThroughItsOwnCustomer() throws Exception {
        UUID store = fixtures.newStore();
        UUID customer = fixtures.newCustomer(CustomersFixtures.pos(store), "Address " + token(), null, null);
        UUID other = fixtures.newCustomer(CustomersFixtures.pos(store), "Other " + token(), null, null);
        UUID address = addAddress(customer, "DELIVERY", "Rua da Entrega 1");

        assertThat(customers.address(customer, address)).hasValue(new AddressSnapshot(address, "DELIVERY",
                "Rua da Entrega 1", null, null, null, null, null));
        assertThat(customers.address(other, address)).isEmpty();
        assertThat(customers.address(customer, UUID.randomUUID())).isEmpty();
        assertThat(customers.address(customer, null)).isEmpty();
        assertThat(customers.address(null, address)).isEmpty();
    }

    private UUID addAddress(UUID customer, String kind, String line1) throws Exception {
        String body = mvc.perform(idem(post(BO + "/" + customer + "/addresses"))
                        .with(account(null, Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind": "%s", "line1": "%s"}""".formatted(kind, line1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(CustomersFixtures.JSON.readTree(body).get("id").asString());
    }
}
