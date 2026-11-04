package com.xavier.backend.cash;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores (through the organization HTTP API, as an installation admin), registers and open sessions for the cash
 * tests, plus the actors: a BackOffice account scoped to a store, and a POS employee on a terminal of a store.
 */
@TestComponent
public class CashFixtures {

    public static final String REGISTERS = "/api/v1/backoffice/cash/registers";
    public static final String SESSIONS = "/api/v1/pos/cash/sessions";
    public static final String MOVEMENTS = "/api/v1/pos/cash/movements";
    public static final String POS_CLOSINGS = "/api/v1/pos/cash/closings";
    public static final String BO_CLOSINGS = "/api/v1/backoffice/cash/closings";
    public static final String POS_DAYS = "/api/v1/pos/cash/days";
    public static final String BO_DAYS = "/api/v1/backoffice/cash/days";
    public static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-7000-8000-0000000000ca");
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    CashFixtures(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    public UUID newStore() throws Exception {
        return newStore(LocalTime.of(5, 0));
    }

    public UUID newStore(LocalTime cutoff) throws Exception {
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(account(null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo", "businessDayCutoff": "%s"}"""
                                .formatted(code, cutoff)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public UUID newRegister(UUID store) throws Exception {
        String code = "R" + UUID.randomUUID().toString().substring(0, 8);
        return id(mvc.perform(idem(post(REGISTERS)).with(account(store, Permission.CASH_REGISTER_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "%s", "name": "Caixa"}""".formatted(store, code)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Opens a session on {@code register} for {@code operator}; returns the session id. */
    public UUID openSession(Pos operator, UUID register, String openingFloat) throws Exception {
        return id(mvc.perform(idem(post(SESSIONS)).with(operator.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(openSessionBody(register, openingFloat)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query(Long.class).single();
    }

    public static String openSessionBody(UUID register, String openingFloat) {
        return """
                {"registerId": "%s", "openingFloat": %s}""".formatted(register, openingFloat);
    }

    /** A POST/PUT with a fresh Idempotency-Key and CSRF. */
    public static MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request) {
        return request.header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf());
    }

    /** A BackOffice account holding {@code permissions} in {@code store} (installation-wide when null). */
    public static RequestPostProcessor account(UUID store, Permission... permissions) {
        Set<String> authorities = Arrays.stream(permissions)
                .map(p -> store == null ? p.authority() : p.authority(store)).collect(Collectors.toSet());
        return authenticated(Actor.account(ACCOUNT_ID, authorities));
    }

    /** A new POS operator (employee on a terminal) of {@code store}. */
    public static Pos pos(UUID store) {
        return new Pos(UUID.randomUUID(), UUID.randomUUID(), store);
    }

    public static UUID id(String json) {
        return UUID.fromString(JSON.readTree(json).get("id").asString());
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    /** Fires {@code request} twice at the same instant (latch) and returns both results. */
    public static <T> List<T> race(Callable<T> request) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<T> gated = () -> {
            start.await();
            return request.call();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<T> first = pool.submit(gated);
            Future<T> second = pool.submit(gated);
            start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    static RequestPostProcessor authenticated(Actor actor) {
        return authentication(authenticationOf(actor));
    }

    public static Authentication authenticationOf(Actor actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.authorities().stream().map(SimpleGrantedAuthority::new).toList());
    }

    /** A POS session: an employee on an enrolled terminal of a store. */
    public record Pos(UUID employeeId, UUID terminalId, UUID storeId) {

        public RequestPostProcessor with(Permission... permissions) {
            return authenticated(actor(permissions));
        }

        public Actor actor(Permission... permissions) {
            return Actor.employee(employeeId, terminalId, storeId, Arrays.stream(permissions)
                    .map(p -> p.authority(storeId)).collect(Collectors.toSet()));
        }
    }
}
