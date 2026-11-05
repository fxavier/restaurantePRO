package com.xavier.backend.cash;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.Money;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Currency;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
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

    /** The timezone of every test store: UTC+2 all year (no DST). */
    public static final ZoneId STORE_ZONE = ZoneId.of("Africa/Maputo");

    private static final Currency MZN = Currency.getInstance("MZN");

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final CashApi cash;
    private final TransactionTemplate tx;

    CashFixtures(MockMvc mvc, JdbcClient jdbc, Clock clock, CashApi cash, TransactionTemplate tx) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.clock = clock;
        this.cash = cash;
        this.tx = tx;
    }

    /** Closes {@code session} as its operator would through settlement, counting {@code countedCash} in cash. */
    public void closeSession(Pos operator, UUID session, String countedCash) {
        as(operator.actor(Permission.CASH_OPERATE), () -> cash.closeSession(session,
                cash.lockSession(session).version(), List.of(new MethodCount("CASH", Money.of(countedCash, MZN))),
                operator.employeeId()));
    }

    /** The cash day of {@code session}. */
    public UUID dayOf(UUID session) {
        return cash.sessionDay(session).cashDayId();
    }

    /** OPEN → OPERATIONALLY_CLOSED as {@code operator}, as settlement would. */
    public void closeDayOperationally(Pos operator, UUID day) {
        as(operator.actor(Permission.CASH_OPERATE), () -> cash.closeDayOperationally(day,
                cash.findDay(day).orElseThrow().version(), operator.employeeId(), ActorKind.EMPLOYEE));
    }

    /**
     * Drafts the closing of {@code register} for {@code day} at the POS, justifies every line, and submits it;
     * returns the closing id.
     */
    public UUID submittedClosing(Pos operator, UUID register, UUID day) throws Exception {
        JsonNode draft = json(mvc.perform(idem(post(POS_CLOSINGS)).with(operator.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"registerId": "%s", "cashDayId": "%s"}""".formatted(register, day)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID closing = UUID.fromString(draft.get("id").asString());
        String justifications = draft.get("lines").valueStream()
                .map(line -> """
                        {"methodCode": "%s", "text": "counted again"}""".formatted(line.get("methodCode").asString()))
                .collect(Collectors.joining(","));
        mvc.perform(idem(put(POS_CLOSINGS + "/" + closing)).with(operator.with(Permission.CASH_OPERATE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version": %d, "justifications": [%s]}""".formatted(draft.get("version").asLong(),
                                justifications)))
                .andExpect(status().isOk());
        mvc.perform(idem(post(POS_CLOSINGS + "/" + closing + "/submit")).with(operator.with(Permission.CASH_OPERATE)))
                .andExpect(status().isOk());
        return closing;
    }

    public void approveClosing(UUID store, UUID closing) throws Exception {
        mvc.perform(idem(post(BO_CLOSINGS + "/" + closing + "/approve"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE))).andExpect(status().isOk());
    }

    public void closeDayFinancially(UUID store, UUID day) throws Exception {
        mvc.perform(idem(post(BO_DAYS + "/" + day + "/financial-close"))
                .with(account(store, Permission.CASH_CLOSING_APPROVE))).andExpect(status().isOk());
    }

    /** Runs {@code action} in a transaction with {@code actor} as the current actor, then restores the caller's. */
    public void as(Actor actor, Runnable action) {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(authenticationOf(actor));
        try {
            tx.executeWithoutResult(status -> action.run());
        } finally {
            SecurityContextHolder.getContext().setAuthentication(caller);
        }
    }

    /**
     * A store whose business-day cutoff is about 12 hours away from the application clock, so a test never crosses
     * it by accident (that made day-dependent tests fail when run just after 05:00 Maputo). Tests about the cutoff
     * pass one explicitly to {@link #newStore(LocalTime)} or {@link #setCutoff}.
     */
    public UUID newStore() throws Exception {
        return newStore(cutoffFarFromNow());
    }

    /** A cutoff about 12 hours away from the application clock's local time in {@link #STORE_ZONE}. */
    public LocalTime cutoffFarFromNow() {
        return LocalDateTime.ofInstant(clock.instant(), STORE_ZONE).plusHours(12).toLocalTime()
                .truncatedTo(ChronoUnit.HOURS);
    }

    /** Changes the business-day cutoff of {@code store} (BackOffice store update with its current version). */
    public void setCutoff(UUID store, LocalTime cutoff) throws Exception {
        JsonNode current = JSON.readTree(mvc.perform(get("/api/v1/backoffice/stores/" + store)
                        .with(account(null, Permission.STORE_MANAGE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/v1/backoffice/stores/" + store).with(account(null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "timezone": "%s", "businessDayCutoff": "%s",
                                 "active": true, "version": %d}""".formatted(current.get("code").asString(),
                                current.get("name").asString(), current.get("timezone").asString(), cutoff,
                                current.get("version").asLong())))
                .andExpect(status().isOk());
    }

    public UUID newStore(LocalTime cutoff) throws Exception {
        return newStore(cutoff, STORE_ZONE);
    }

    /** A store in {@code zone}, e.g. one with daylight saving time. */
    public UUID newStore(LocalTime cutoff, ZoneId zone) throws Exception {
        String code = "C" + UUID.randomUUID().toString().substring(0, 8);
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(account(null, Permission.STORE_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Store", "timezone": "%s", "businessDayCutoff": "%s"}"""
                                .formatted(code, zone.getId(), cutoff)))
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
        return idem(request, UUID.randomUUID().toString());
    }

    /** A POST/PUT with the given Idempotency-Key and CSRF, e.g. to replay a request. */
    public static MockHttpServletRequestBuilder idem(MockHttpServletRequestBuilder request, String key) {
        return request.header("Idempotency-Key", key).with(csrf());
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

    /** Fires {@code first} and {@code second} at the same instant (latch) and returns both results, in order. */
    public static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<T> a = pool.submit(gated(start, first));
            Future<T> b = pool.submit(gated(start, second));
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private static <T> Callable<T> gated(CountDownLatch start, Callable<T> request) {
        return () -> {
            start.await();
            return request.call();
        };
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
