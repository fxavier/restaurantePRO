package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.MfaFixtures;
import com.xavier.backend.identity.MfaTest;
import com.xavier.backend.identity.MutableClock;
import com.xavier.backend.identity.SwitchableMfaPolicy;
import com.xavier.backend.identity.mfa.MfaMode;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/**
 * A59 (D142): with mode SENSITIVE every one of the 15 sensitive permissions is refused through a real endpoint until
 * the session steps up, is served after it, and is refused again once the proof is older than 15 minutes. A POS (PIN)
 * session on a BackOffice sensitive route gets MFA_ACCOUNT_REQUIRED in SENSITIVE and passes with OFF; approver PINs
 * are never asked for a code. Each case holds only the sensitive permission and the non-sensitive ones the use case
 * also checks, so the refusal can only come from the step-up rule.
 */
@MfaTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepUpTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    SwitchableMfaPolicy policy;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApproverVerifier approvers;

    IdentityFixtures fixtures;
    MfaFixtures mfa;
    UUID store;
    UUID draftRelease;
    UUID cashSession;
    UUID cashDay;

    @BeforeAll
    void resources() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        mfa = new MfaFixtures(mvc, clock);
        store = storeWithCutoffFarFromNow();
        draftRelease = UUID.fromString(new Publications(mvc, jdbc).createDraft(store).get("id").asString());
    }

    @AfterEach
    void reset() {
        policy.reset();
    }

    /** The cases cover exactly the D142 set: a new sensitive permission without a case fails here. */
    @Test
    void everySensitivePermissionHasACase() {
        assertThat(Arrays.stream(Permission.values()).filter(Permission::sensitive).collect(Collectors.toSet()))
                .isEqualTo(Stream.of(Case.values()).map(Case::permission).collect(Collectors.toSet()))
                .hasSize(15);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void aSensitivePermissionNeedsAFreshSecondFactor(Case c) throws Exception {
        MfaFixtures.Enrolled enrolled = enrolledWith(c);
        Cookie session = mfa.login(enrolled.user().username(), MfaTest.LAN_ADDRESS); // state NONE: never proved
        String key = UUID.randomUUID().toString();
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(c.request(this).cookie(session).header("Idempotency-Key", key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"))
                .andExpect(jsonPath("$.stepUpUri").value("/api/v1/auth/mfa/step-up"));

        mvc.perform(MfaFixtures.stepUp(session, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.stepUpValidUntil").isNotEmpty());
        int served = mvc.perform(c.request(this).cookie(session).header("Idempotency-Key", key)).andReturn()
                .getResponse().getStatus();
        assertThat(served).as("%s after step-up", c).isNotEqualTo(403).isLessThan(500);

        clock.advance(Duration.ofMinutes(16));
        mvc.perform(c.request(this).cookie(session).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_STEP_UP_REQUIRED"));
    }

    @Test
    void fifteenMinutesAfterTheStepUpItStillHolds() throws Exception {
        MfaFixtures.Enrolled enrolled = enrolledWith(Case.USER_MANAGE);
        Cookie session = mfa.login(enrolled.user().username(), MfaTest.LAN_ADDRESS);
        policy.set(MfaMode.SENSITIVE);
        mvc.perform(MfaFixtures.stepUp(session, mfa.nextCode(enrolled.secret()))).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(15));
        mvc.perform(get("/api/v1/backoffice/users").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void anAccountWithoutAFactorIsAskedToEnrol() throws Exception {
        UUID role = role(Set.of(Permission.USER_MANAGE));
        IdentityFixtures.User user = fixtures.user(Set.of(role), null);
        Cookie session = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(get("/api/v1/backoffice/users").cookie(session)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ENROLMENT_REQUIRED"));
    }

    @Test
    void aNonSensitivePermissionNeedsNoStepUp() throws Exception {
        MfaFixtures.Enrolled enrolled = mfa.enrolledUser(Set.of(role(Set.of(Permission.STORE_MANAGE))));
        Cookie session = mfa.login(enrolled.user().username(), MfaTest.LAN_ADDRESS);
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(get("/api/v1/backoffice/stores").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void aStepUpNeedsAFactor() throws Exception {
        IdentityFixtures.User user = fixtures.user(Set.of(IdentityFixtures.WAITER), null);
        Cookie session = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);
        policy.set(MfaMode.SENSITIVE);

        mvc.perform(MfaFixtures.stepUp(session, "123456")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MFA_NOT_ENROLLED"));
    }

    // --- POS sessions and approver PINs (D142 amended) ---

    @Test
    void aPosSessionOnABackOfficeSensitiveRouteNeedsABackOfficeAccountWhileEnforced() throws Exception {
        UUID role = role(Set.of(Permission.TERMINAL_MANAGE, Permission.DINING_VIEW));
        IdentityFixtures.Terminal terminal = fixtures.enrolledTerminal(store);
        IdentityFixtures.Employee employee = fixtures.employee("2468", store, role);
        Cookie pos = fixtures.posLogin(terminal, employee, "2468");
        MockHttpServletRequestBuilder terminals = get("/api/v1/backoffice/terminals").param("storeId",
                store.toString());

        mvc.perform(terminals.cookie(pos)).andExpect(status().isOk());

        policy.set(MfaMode.SENSITIVE);
        mvc.perform(get("/api/v1/backoffice/terminals").param("storeId", store.toString()).cookie(pos))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_ACCOUNT_REQUIRED"))
                .andExpect(jsonPath("$.stepUpUri").doesNotExist());

        policy.reset();
        mvc.perform(get("/api/v1/backoffice/terminals").param("storeId", store.toString()).cookie(pos))
                .andExpect(status().isOk());
    }

    @Test
    void anApproverPinIsNeverAskedForASecondFactor() throws Exception {
        UUID role = role(Set.of(Permission.CASH_CLOSING_APPROVE));
        IdentityFixtures.Employee approver = fixtures.employee("1357", store, role);
        policy.set(MfaMode.ALL_BACKOFFICE);

        assertThat(approvers.verify(approver.code(), "1357", Permission.CASH_CLOSING_APPROVE, store))
                .isEqualTo(approver.id());
    }

    @Test
    void aPosUseCaseIsNotAskedForASecondFactor() throws Exception {
        UUID terminal = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        Actor operator = Actor.employee(employee, terminal, store, Set.of(Permission.CATALOG_VIEW.authority(store)));
        policy.set(MfaMode.ALL_BACKOFFICE);

        mvc.perform(get("/api/v1/pos/catalog/daily-dishes").with(SecurityMockMvcRequestPostProcessors.authentication(
                        UsernamePasswordAuthenticationToken.authenticated(operator, null, operator.authorities()
                                .stream().map(SimpleGrantedAuthority::new).toList()))))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
    }

    // --- cases ---

    Stream<Case> cases() {
        return Stream.of(Case.values());
    }

    enum Case {
        COMPANY_MANAGE(Permission.COMPANY_MANAGE),
        USER_MANAGE(Permission.USER_MANAGE),
        ROLE_MANAGE(Permission.ROLE_MANAGE),
        EMPLOYEE_MANAGE(Permission.EMPLOYEE_MANAGE),
        TERMINAL_MANAGE(Permission.TERMINAL_MANAGE),
        SUPPORT_ACCESS_MANAGE(Permission.SUPPORT_ACCESS_MANAGE),
        FISCAL_MANAGE(Permission.FISCAL_MANAGE),
        CATALOG_PUBLISH(Permission.CATALOG_PUBLISH),
        PROMOTION_MANAGE(Permission.PROMOTION_MANAGE),
        CASH_CLOSING_APPROVE(Permission.CASH_CLOSING_APPROVE, Permission.CASH_VIEW),
        CASH_ADJUST(Permission.CASH_ADJUST, Permission.CASH_VIEW),
        CREDIT_LIMIT_MANAGE(Permission.CREDIT_LIMIT_MANAGE, Permission.CUSTOMER_VIEW),
        PAYMENT_METHOD_MANAGE(Permission.PAYMENT_METHOD_MANAGE),
        REPORT_EXPORT(Permission.REPORT_EXPORT, Permission.REPORT_VIEW),
        AUDIT_EXPORT(Permission.AUDIT_EXPORT, Permission.AUDIT_VIEW);

        private final Permission permission;
        private final Set<Permission> companions;

        Case(Permission permission, Permission... companions) {
            this.permission = permission;
            this.companions = companions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(
                    Arrays.asList(companions));
        }

        Permission permission() {
            return permission;
        }

        Set<Permission> held() {
            Set<Permission> held = new HashSet<>(companions);
            held.add(permission);
            return held;
        }

        MockHttpServletRequestBuilder request(StepUpTest t) throws Exception {
            return switch (this) {
                case COMPANY_MANAGE -> get("/api/v1/backoffice/company-profile");
                case USER_MANAGE -> get("/api/v1/backoffice/users");
                case ROLE_MANAGE -> get("/api/v1/backoffice/roles");
                case EMPLOYEE_MANAGE -> get("/api/v1/backoffice/employees");
                case TERMINAL_MANAGE -> get("/api/v1/backoffice/terminals").param("storeId", t.store.toString());
                case SUPPORT_ACCESS_MANAGE -> get("/api/v1/backoffice/support-grants");
                case FISCAL_MANAGE -> get("/api/v1/backoffice/fiscal/series");
                case CATALOG_PUBLISH -> jsonPost(Publications.RELEASES + "/" + t.draftRelease + "/publish", "{}");
                case PROMOTION_MANAGE -> put("/api/v1/backoffice/promotions/discount-limits/" + t.store).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("freeLimitPercent", 5, "maxPercent", 10, "version", null));
                case CASH_CLOSING_APPROVE -> jsonPost("/api/v1/backoffice/cash/days/" + t.cashDay
                        + "/financial-close", "{}");
                case CASH_ADJUST -> jsonPost("/api/v1/backoffice/cash/sessions/" + t.cashSession + "/adjustments",
                        json("direction", "IN", "methodCode", "CASH", "amount", 1, "reason", "count error"));
                case CREDIT_LIMIT_MANAGE -> jsonPost("/api/v1/backoffice/customers/" + UUID.randomUUID()
                        + "/credit-limit-changes", json("newLimit", 100, "reason", "good customer"));
                case PAYMENT_METHOD_MANAGE -> get("/api/v1/backoffice/payment-methods");
                case REPORT_EXPORT -> jsonPost("/api/v1/backoffice/reports/exports", json("report", "sales",
                        "params", java.util.Map.of("storeId", t.store.toString(), "from", "2026-01-01",
                                "to", "2026-01-02")));
                case AUDIT_EXPORT -> jsonPost("/api/v1/backoffice/audit/exports", json("from",
                        "2026-01-01T00:00:00Z", "to", "2026-01-02T00:00:00Z"));
            };
        }

        private static MockHttpServletRequestBuilder jsonPost(String path, String body) {
            return post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
        }
    }

    // --- helpers ---

    private MfaFixtures.Enrolled enrolledWith(Case c) throws Exception {
        if (c == Case.CASH_ADJUST || c == Case.CASH_CLOSING_APPROVE) {
            openCashSession();
        }
        return mfa.enrolledUser(Set.of(role(c.held())));
    }

    private UUID role(Set<Permission> permissions) throws Exception {
        return IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/roles").with(IdentityFixtures.admin())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", IdentityFixtures.unique("r"), "permissions", permissions)))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    /** A real cash session and its day in {@link #store}, opened by a POS operator (the cash module's own path). */
    private void openCashSession() throws Exception {
        if (cashSession != null) {
            return;
        }
        UUID register = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/cash/registers")
                        .with(IdentityFixtures.admin()).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", store, "code", IdentityFixtures.unique("R"), "name", "Caixa")))
                .andExpect(status().isCreated()).andReturn().getResponse());
        Actor operator = Actor.employee(UUID.randomUUID(), UUID.randomUUID(), store,
                Set.of(Permission.CASH_OPERATE.authority(store)));
        cashSession = IdentityFixtures.id(mvc.perform(post("/api/v1/pos/cash/sessions")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                UsernamePasswordAuthenticationToken.authenticated(operator, null,
                                        operator.authorities().stream().map(SimpleGrantedAuthority::new).toList())))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("registerId", register, "openingFloat", 100)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        cashDay = jdbc.sql("SELECT cash_day_id FROM cash.cash_session WHERE id = ?").param(cashSession)
                .query(UUID.class).single();
    }

    private UUID storeWithCutoffFarFromNow() throws Exception {
        String cutoff = LocalDateTime.ofInstant(clock.instant(), MAPUTO).plusHours(12).toLocalTime()
                .truncatedTo(ChronoUnit.HOURS).toString();
        return IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/stores").with(IdentityFixtures.admin())
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", IdentityFixtures.unique("S"), "name", "Store", "timezone",
                                MAPUTO.getId(), "businessDayCutoff", cutoff)))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }
}
