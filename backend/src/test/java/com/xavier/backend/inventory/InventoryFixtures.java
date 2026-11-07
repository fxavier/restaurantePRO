package com.xavier.backend.inventory;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import java.util.Arrays;
import java.util.UUID;
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
 * Stores (organization HTTP API), stock items and warehouses created through the inventory HTTP API, and the
 * BackOffice actors. Codes are random so tests sharing the database never collide.
 */
@TestComponent
public class InventoryFixtures {

    public static final String BO = "/api/v1/backoffice/inventory";
    public static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000d7");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    InventoryFixtures(MockMvc mvc) {
        this.mvc = mvc;
    }

    public UUID newStore() throws Exception {
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(account(null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo", "businessDayCutoff": "05:00"}"""
                                .formatted(code("S"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public static final java.time.Instant ACTIVE_FROM = java.time.Instant.parse("2026-11-01T05:00:00Z");

    /** A store where inventory is live (D122) since {@link #ACTIVE_FROM}, so manual movements are allowed. */
    public UUID newActiveStore() throws Exception {
        UUID store = newStore();
        mvc.perform(idem(post(BO + "/activations")).with(account(store, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(store, ACTIVE_FROM)))
                .andExpect(status().isCreated());
        return store;
    }

    /** A PURCHASED stock item with base unit {@code baseUnit}. */
    public UUID newStockItem(String baseUnit) throws Exception {
        return id(mvc.perform(idem(post(BO + "/stock-items")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Item", "baseUnit": "%s", "kind": "PURCHASED"}"""
                                .formatted(code("I"), baseUnit)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public UUID newWarehouse(UUID store, String negativePolicy) throws Exception {
        return id(mvc.perform(idem(post(BO + "/warehouses")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "code": "%s", "name": "Store-room", "negativePolicy": "%s"}"""
                                .formatted(store, code("W"), negativePolicy)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A warehouse (ALLOW) of a new store, for callers that cannot throw. */
    public UUID newWarehouseQuietly() {
        try {
            return newWarehouse(newStore(), "ALLOW");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public static Authentication authenticationOf(Actor actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
    }

    public static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }
}
