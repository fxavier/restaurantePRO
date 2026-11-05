package com.xavier.backend.customers;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.Money;
import java.util.Arrays;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores (organization HTTP API, as an installation admin) for POS sessions, customers created through the HTTP API,
 * and the actors: a BackOffice account, and a POS employee on a terminal of a store.
 */
@TestComponent
public class CustomersFixtures {

    public static final String POS = "/api/v1/pos/customers";
    public static final String BO = "/api/v1/backoffice/customers";
    public static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000c5");
    public static final Currency MZN = Currency.getInstance("MZN");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    CustomersFixtures(MockMvc mvc) {
        this.mvc = mvc;
    }

    public UUID newStore() throws Exception {
        String code = "S" + UUID.randomUUID().toString().substring(0, 8);
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(account(null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo", "businessDayCutoff": "05:00"}"""
                                .formatted(code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A customer created at the POS (quick create); {@code phone} and {@code taxId} may be null. */
    public UUID newCustomer(Pos pos, String name, String phone, String taxId) throws Exception {
        return id(mvc.perform(idem(post(POS)).with(pos.with(Permission.CUSTOMER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content(customerBody(name, phone, taxId)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A new customer with a credit account of {@code limit} (created at a new store's POS, limit set at the BackOffice). */
    public UUID creditCustomer(String limit) throws Exception {
        UUID customer = newCustomer(pos(newStore()), "Credit " + token(), null, null);
        setLimit(customer, limit);
        return customer;
    }

    public void setLimit(UUID customer, String limit) throws Exception {
        mvc.perform(idem(post(BO + "/" + customer + "/credit-limit-changes"))
                        .with(account(null, Permission.CREDIT_LIMIT_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newLimit": %s, "reason": "test"}""".formatted(limit)))
                .andExpect(status().isOk());
    }

    public static String customerBody(String name, String phone, String taxId) {
        return """
                {"name": %s, "phone": %s, "taxId": %s}""".formatted(quoted(name), quoted(phone), quoted(taxId));
    }

    static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /** {@code n} random digits, so phones and tax ids of different tests never collide. */
    public static String digits(int n) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < n; i++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        return digits.toString();
    }

    /** A random lower-case token of letters only (a name part no other test uses). */
    public static String token() {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            token.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        return token.toString();
    }

    public static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }

    /** A POST/PUT with a fresh Idempotency-Key and CSRF. */
    public static MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request) {
        return idem(request, UUID.randomUUID().toString());
    }

    public static MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request, String key) {
        return request.header("Idempotency-Key", key).with(csrf());
    }

    /** A BackOffice account holding {@code permissions} in {@code store} (installation-wide when null). */
    public static RequestPostProcessor account(UUID store, Permission... permissions) {
        return authentication(authenticationOf(accountActor(store, permissions)));
    }

    public static Actor accountActor(UUID store, Permission... permissions) {
        return Actor.account(ACCOUNT_ID, Arrays.stream(permissions)
                .map(p -> store == null ? p.authority() : p.authority(store)).collect(Collectors.toSet()));
    }

    public static Pos pos(UUID store) {
        return new Pos(UUID.randomUUID(), UUID.randomUUID(), store);
    }

    public static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    public static Authentication authenticationOf(Actor actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
    }

    /** A POS session: an employee on an enrolled terminal of a store. */
    public record Pos(UUID employeeId, UUID terminalId, UUID storeId) {

        public RequestPostProcessor with(Permission... permissions) {
            return authentication(authenticationOf(actor(permissions)));
        }

        public Actor actor(Permission... permissions) {
            return Actor.employee(employeeId, terminalId, storeId, Arrays.stream(permissions)
                    .map(p -> p.authority(storeId)).collect(Collectors.toSet()));
        }
    }
}
