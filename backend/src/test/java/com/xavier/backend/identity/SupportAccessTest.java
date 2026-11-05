package com.xavier.backend.identity;

import static com.xavier.backend.identity.IdentityFixtures.JSON;
import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.loginRequest;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures.User;
import com.xavier.backend.identity.service.SupportGrantExpiry;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Support access (spec §5 "temporário, autorizado e auditado", P36, D83, A34): an administrator grants a support
 * account at most 24 h; login needs an active grant; the grant ends by itself or early, ending its sessions; every
 * step is audited. Time moves only through the injected {@link MutableClock}, never by sleeping.
 */
@IdentityTest
@Import(MutableClock.Config.class)
@TestPropertySource(properties = "restaurantpro.identity.support-expiry-cron=-") // the job runs only when a test calls it
class SupportAccessTest {

    static final String GRANTS = "/api/v1/backoffice/support-grants";
    static final String PASSWORD = "initial password 1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SupportGrantExpiry expiry;

    IdentityFixtures fixtures;
    User administrator;

    @BeforeEach
    void fixtures() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        administrator = fixtures.user(Set.of(IdentityFixtures.ADMINISTRATOR), null);
    }

    // --- login needs an active grant (A34) ---

    @Test
    void aSupportAccountWithoutAGrantGetsExactlyTheBadPasswordAnswer() throws Exception {
        User support = supportAccount(Set.of());
        User other = fixtures.newUser(PASSWORD, Set.of());
        String badPassword = mvc.perform(loginRequest(other.username(), "wrong password 1"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();

        mvc.perform(loginRequest(support.username(), PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(badPassword, JsonCompareMode.STRICT));
    }

    @Test
    void aSupportAccountWithATwoHourGrantLogsInAndTheLoginNamesTheGrant() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 120);

        fixtures.login(support.username(), PASSWORD);

        assertThat(jdbc.sql("""
                        SELECT details ->> 'supportGrantId' FROM audit.audit_event
                        WHERE action = 'USER_LOGGED_IN' AND entity_id = :id""")
                .param("id", support.id().toString()).query(String.class).list()).containsExactly(grant.toString());
    }

    @Test
    void afterTheGrantEndsLoginIsRefusedEvenBeforeTheExpiryJobRuns() throws Exception {
        User support = supportAccount(Set.of());
        grant(support, 120);
        clock.advance(Duration.ofMinutes(120));

        mvc.perform(loginRequest(support.username(), PASSWORD)).andExpect(status().isUnauthorized());
    }

    @Test
    void aSupportSessionLastsAtMostTheRestOfTheGrant() throws Exception {
        User support = supportAccount(Set.of());
        grant(support, 10);
        clock.advance(Duration.ofMinutes(4));

        Cookie session = fixtures.login(support.username(), PASSWORD);

        assertThat(maxInactiveSeconds(session)).isEqualTo(Duration.ofMinutes(6).toSeconds());
    }

    @Test
    void anOrdinaryAccountKeepsTheDefaultSessionTimeout() throws Exception {
        User user = fixtures.newUser(PASSWORD, Set.of());

        Cookie session = fixtures.login(user.username(), PASSWORD);

        assertThat(maxInactiveSeconds(session)).isEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    // --- a support session checks its grant on every request (no reliance on deletion) ---

    @Test
    void aRevokeThatFoundNoSessionToDeleteStillEndsTheSessionAtItsNextRequest() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 120);
        Cookie session = fixtures.login(support.username(), PASSWORD);
        // as if the revoke had committed between the login's grant check and the session's save: no row deleted
        jdbc.sql("UPDATE identity.support_grant SET revoked_at = now(), revoked_by_account_id = :admin WHERE id = :id")
                .param("admin", administrator.id()).param("id", grant).update();

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(unauthenticatedBody(), JsonCompareMode.STRICT));
        assertThat(sessionCount(support)).isZero();
    }

    @Test
    void aSessionInUseThatCrossesTheGrantEndGets401WithoutTheJob() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 10);
        Cookie session = fixtures.login(support.username(), PASSWORD);
        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(10));

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(unauthenticatedBody(), JsonCompareMode.STRICT));
        assertThat(jdbc.sql("SELECT expired_at IS NULL FROM identity.support_grant WHERE id = :id")
                .param("id", grant).query(Boolean.class).single()).isTrue();
    }

    @Test
    void anOrdinarySessionIsNotCheckedAgainstAnyGrant() throws Exception {
        User user = fixtures.newUser(PASSWORD, Set.of());
        Cookie session = fixtures.login(user.username(), PASSWORD);
        clock.advance(Duration.ofHours(25));

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk());
    }

    @Test
    void aGrantPastItsEndCannotBeRevokedBeforeTheJobRuns() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 30);
        clock.advance(Duration.ofMinutes(30));

        mvc.perform(revoke(grant).with(asAdministrator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPORT_GRANT_ENDED"));
    }

    @Test
    void aNewGrantAfterAnUnsweptEndExpiresTheOldOneFirst() throws Exception {
        User support = supportAccount(Set.of());
        UUID old = grant(support, 30);
        Cookie oldSession = fixtures.login(support.username(), PASSWORD);
        clock.advance(Duration.ofMinutes(30));

        grant(support, 60);

        assertThat(jdbc.sql("""
                        SELECT actor_kind FROM audit.audit_event
                        WHERE action = 'SUPPORT_GRANT_EXPIRED' AND entity_id = :id""")
                .param("id", old.toString()).query(String.class).list()).containsExactly("SYSTEM");
        mvc.perform(get("/api/v1/auth/session").cookie(oldSession)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(support.username(), PASSWORD).cookie(oldSession)).andExpect(status().isOk());
    }

    @Test
    void changingASupportAccountsRolesOrActiveFlagNeedsSupportAccessManage() throws Exception {
        User support = supportAccount(Set.of());
        RequestPostProcessor userManager = as(Set.of(Permission.USER_MANAGE.authority()));

        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(userManager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", false, "version", 0)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(userManager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", true, "version", 0)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(as(Set.of(Permission.USER_MANAGE.authority(),
                                Permission.SUPPORT_ACCESS_MANAGE.authority()))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", false, "version", 0)))
                .andExpect(status().isOk());
    }

    @Test
    void addingARoleToASupportAccountNeedsSupportAccessManage() throws Exception {
        User support = supportAccount(Set.of());
        Set<String> waiterGranter = new java.util.HashSet<>(Set.of(Permission.USER_MANAGE.authority()));
        IdentityFixtures.WAITER_PERMISSIONS.forEach(p -> waiterGranter.add(p.authority()));

        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(as(waiterGranter)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(IdentityFixtures.WAITER), "active", true, "version", 0)))
                .andExpect(status().isForbidden());
    }

    // --- expiry (job, injected clock) ---

    @Test
    void atExpiryTheJobEndsTheGrantRevokesItsSessionsAndAuditsAsTheSystem() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 120);
        Cookie session = fixtures.login(support.username(), PASSWORD);
        clock.advance(Duration.ofMinutes(120));

        expiry.expireDue();

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        assertThat(sessionCount(support)).isZero();
        mvc.perform(get(GRANTS + "?accountId=" + support.id()).with(asAdministrator()))
                .andExpect(jsonPath("$.content[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$.content[0].expiredAt").isNotEmpty());
        assertThat(jdbc.sql("""
                        SELECT actor_kind || '/' || coalesce(actor_id::text, '-') FROM audit.audit_event
                        WHERE action = 'SUPPORT_GRANT_EXPIRED' AND entity_id = :id""")
                .param("id", grant.toString()).query(String.class).list()).containsExactly("SYSTEM/-");
    }

    @Test
    void theJobLeavesAGrantThatHasNotEndedAndItsSessions() throws Exception {
        User support = supportAccount(Set.of());
        grant(support, 120);
        Cookie session = fixtures.login(support.username(), PASSWORD);
        clock.advance(Duration.ofMinutes(119));

        expiry.expireDue();

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk());
        mvc.perform(get(GRANTS + "?accountId=" + support.id()).with(asAdministrator()))
                .andExpect(jsonPath("$.content[0].status").value("ACTIVE"));
    }

    @Test
    void runningTheJobAgainChangesNothing() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 30);
        clock.advance(Duration.ofMinutes(30));
        expiry.expireDue();

        expiry.expireDue();

        assertThat(auditCount("SUPPORT_GRANT_EXPIRED", grant)).isEqualTo(1);
    }

    @Test
    void anExpiredGrantCannotBeRevoked() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 30);
        clock.advance(Duration.ofMinutes(30));
        expiry.expireDue();

        mvc.perform(revoke(grant).with(asAdministrator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPORT_GRANT_ENDED"));
    }

    // --- early revoke ---

    @Test
    void anEarlyRevokeEndsTheSessionsBlocksLoginAndIsAuditedAsTheAdministrator() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 120);
        Cookie session = fixtures.login(support.username(), PASSWORD);

        mvc.perform(revoke(grant).with(asAdministrator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedByAccountId").value(administrator.id().toString()));

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest(support.username(), PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("""
                        SELECT actor_kind || '/' || actor_id FROM audit.audit_event
                        WHERE action = 'SUPPORT_GRANT_REVOKED' AND entity_id = :id""")
                .param("id", grant.toString()).query(String.class).list())
                .containsExactly("ACCOUNT/" + administrator.id());
    }

    @Test
    void aRevokedGrantCannotBeRevokedAgainAndANewGrantCanFollowIt() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 120);
        mvc.perform(revoke(grant).with(asAdministrator())).andExpect(status().isOk());

        mvc.perform(revoke(grant).with(asAdministrator()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"status": 409, "title": "Conflict", "code": "SUPPORT_GRANT_ENDED",
                         "detail": "The support grant has already ended.",
                         "instance": "%s/%s/revoke"}""".formatted(GRANTS, grant), JsonCompareMode.STRICT));
        grant(support, 60);
        fixtures.login(support.username(), PASSWORD);
    }

    // --- the grant itself ---

    @Test
    void aGrantLongerThan24HoursIsRefusedAnd24HoursIsAccepted() throws Exception {
        User support = supportAccount(Set.of());

        mvc.perform(grantRequest(support.id(), 1441, "too long").with(asAdministrator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("durationMinutes"));
        mvc.perform(grantRequest(support.id(), 0, "nothing").with(asAdministrator()))
                .andExpect(status().isBadRequest());
        mvc.perform(grantRequest(support.id(), 1440, "a whole day").with(asAdministrator()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.reason").value("a whole day"));
    }

    @Test
    void theGrantRunsFromNowForItsDurationAndIsAudited() throws Exception {
        User support = supportAccount(Set.of());

        JsonNode grant = JSON.readTree(mvc.perform(grantRequest(support.id(), 90, "ticket 42")
                        .with(asAdministrator()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(java.time.Instant.parse(grant.get("validFrom").asString())).isEqualTo(clock.instant());
        assertThat(java.time.Instant.parse(grant.get("validUntil").asString()))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(90)));
        assertThat(grant.get("grantedByAccountId").asString()).isEqualTo(administrator.id().toString());
        assertThat(jdbc.sql("""
                        SELECT actor_id::text || '/' || reason FROM audit.audit_event
                        WHERE action = 'SUPPORT_GRANT_CREATED' AND entity_id = :id""")
                .param("id", grant.get("id").asString()).query(String.class).list())
                .containsExactly(administrator.id() + "/ticket 42");
    }

    @Test
    void aGrantIsOnlyForASupportAccount() throws Exception {
        User ordinary = fixtures.newUser(PASSWORD, Set.of());

        mvc.perform(grantRequest(ordinary.id(), 60, "ticket").with(asAdministrator()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("""
                        {"status": 422, "title": "Unprocessable Content", "code": "SUPPORT_ACCOUNT_REQUIRED",
                         "detail": "Support access is granted only to a support account.",
                         "instance": "%s"}""".formatted(GRANTS), JsonCompareMode.STRICT));
    }

    @Test
    void anUnknownAccountIsNotFound() throws Exception {
        mvc.perform(grantRequest(UUID.randomUUID(), 60, "ticket").with(asAdministrator()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSecondActiveGrantForTheSameAccountIsAConflict() throws Exception {
        User support = supportAccount(Set.of());
        grant(support, 60);

        mvc.perform(grantRequest(support.id(), 60, "again").with(asAdministrator()))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"status": 409, "title": "Conflict", "code": "SUPPORT_GRANT_ACTIVE",
                         "detail": "The account already holds an active support grant.",
                         "instance": "%s"}""".formatted(GRANTS), JsonCompareMode.STRICT));
    }

    @Test
    void twoConcurrentGrantsForOneAccountGiveOneGrantAndOneConflict() throws Exception {
        User support = supportAccount(Set.of());
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> grantOnce = () -> {
            start.await();
            return mvc.perform(grantRequest(support.id(), 60, "race").with(asAdministrator()))
                    .andReturn().getResponse().getStatus();
        };
        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(grantOnce);
            Future<Integer> second = pool.submit(grantOnce);
            start.countDown();
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.sql("SELECT count(*) FROM identity.support_grant WHERE account_id = :a")
                .param("a", support.id()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRepeatedGrantWithTheSameKeyGrantsOnce() throws Exception {
        User support = supportAccount(Set.of());
        String key = UUID.randomUUID().toString();

        String first = mvc.perform(grantRequest(support.id(), 60, "ticket", key)
                .with(asAdministrator())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(grantRequest(support.id(), 60, "ticket", key)
                .with(asAdministrator())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(JSON.readTree(replay).get("id")).isEqualTo(JSON.readTree(first).get("id"));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'SUPPORT_GRANT_CREATED' "
                        + "AND details ->> 'accountId' = :a")
                .param("a", support.id().toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void auditDetailsNameTheGrantAndCarryNoSecrets() throws Exception {
        User support = supportAccount(Set.of());
        UUID revoked = grant(support, 60);
        fixtures.login(support.username(), PASSWORD);
        mvc.perform(revoke(revoked).with(asAdministrator())).andExpect(status().isOk());
        UUID expired = grant(support, 5);
        fixtures.login(support.username(), PASSWORD);
        clock.advance(Duration.ofMinutes(5));
        expiry.expireDue();

        List<String> keys = jdbc.sql("""
                        SELECT DISTINCT action || ':' || k FROM audit.audit_event, jsonb_object_keys(details) k
                        WHERE entity_type = 'SupportGrant' AND entity_id IN (:ids)""")
                .param("ids", List.of(revoked.toString(), expired.toString())).query(String.class).list();
        assertThat(keys).containsExactlyInAnyOrder(
                "SUPPORT_GRANT_CREATED:accountId", "SUPPORT_GRANT_CREATED:validFrom",
                "SUPPORT_GRANT_CREATED:validUntil",
                "SUPPORT_GRANT_REVOKED:accountId", "SUPPORT_GRANT_REVOKED:endedSessions",
                "SUPPORT_GRANT_EXPIRED:accountId", "SUPPORT_GRANT_EXPIRED:endedSessions");
        assertThat(jdbc.sql("""
                        SELECT details ->> 'endedSessions' FROM audit.audit_event
                        WHERE entity_type = 'SupportGrant' AND action <> 'SUPPORT_GRANT_CREATED'
                          AND entity_id IN (:ids)""")
                .param("ids", List.of(revoked.toString(), expired.toString())).query(String.class).list())
                .containsExactly("1", "1");
    }

    // --- who may grant ---

    @Test
    void anAccountWithoutSupportAccessManageCannotGrantListOrRevoke() throws Exception {
        User support = supportAccount(Set.of());
        UUID grant = grant(support, 60);
        Set<String> allButSupport = Arrays.stream(Permission.values()).filter(p -> p != Permission.SUPPORT_ACCESS_MANAGE)
                .map(Permission::authority).collect(Collectors.toSet());

        mvc.perform(grantRequest(support.id(), 60, "x").with(as(allButSupport))).andExpect(status().isForbidden());
        mvc.perform(get(GRANTS).with(as(allButSupport))).andExpect(status().isForbidden());
        mvc.perform(revoke(grant).with(as(allButSupport))).andExpect(status().isForbidden());
    }

    @Test
    void aSeededManagerCannotGrantOnlyTheAdministratorRoleCan() throws Exception {
        User manager = fixtures.user(Set.of(IdentityFixtures.MANAGER), null);
        User support = supportAccount(Set.of());
        Cookie session = fixtures.login(manager.username(), IdentityFixtures.STRONG_PASSWORD);

        mvc.perform(grantRequest(support.id(), 60, "x").cookie(session)).andExpect(status().isForbidden());

        Cookie adminSession = fixtures.login(administrator.username(), IdentityFixtures.STRONG_PASSWORD);
        mvc.perform(grantRequest(support.id(), 60, "x").cookie(adminSession)).andExpect(status().isCreated());
    }

    @Test
    void creatingASupportAccountNeedsSupportAccessManage() throws Exception {
        mvc.perform(createSupportAccount(unique("sup"), null).with(as(Set.of(Permission.USER_MANAGE.authority()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSupportAccountIsNeverLinkedToAnEmployee() throws Exception {
        UUID store = fixtures.store();
        IdentityFixtures.Employee employee = fixtures.employee("4821", store, IdentityFixtures.WAITER);

        mvc.perform(createSupportAccount(unique("sup"), employee.id()).with(admin()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPORT_ACCOUNT_LINKED"));
    }

    @Test
    void anExistingSupportAccountCannotBeLinkedToAnEmployeeLater() throws Exception {
        User support = supportAccount(Set.of());
        UUID store = fixtures.store();
        IdentityFixtures.Employee employee = fixtures.employee("4821", store, IdentityFixtures.WAITER);

        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("employeeId", employee.id(), "roleIds", Set.of(), "active", true,
                                "version", 0)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SUPPORT_ACCOUNT_LINKED"));
    }

    // --- a support user cannot grant or escalate ---

    @Test
    void aSupportSessionWithTheAdministratorRoleHoldsNoAccessManagingPermission() throws Exception {
        Cookie session = supportSession(Set.of(IdentityFixtures.ADMINISTRATOR));

        JsonNode view = JSON.readTree(mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Set<String> permissions = new java.util.HashSet<>();
        view.get("permissions").forEach(p -> permissions.add(p.asString()));

        assertThat(permissions).contains(Permission.CATALOG_MANAGE.authority())
                .doesNotContain(Permission.SUPPORT_ACCESS_MANAGE.authority(), Permission.USER_MANAGE.authority(),
                        Permission.ROLE_MANAGE.authority(), Permission.EMPLOYEE_MANAGE.authority(),
                        Permission.TERMINAL_MANAGE.authority());
    }

    @Test
    void aSupportUserCannotGrantSupportAccess() throws Exception {
        Cookie session = supportSession(Set.of(IdentityFixtures.ADMINISTRATOR));
        User otherSupport = supportAccount(Set.of());

        mvc.perform(grantRequest(otherSupport.id(), 60, "self-service").cookie(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSupportActorIsRefusedEvenIfItsSessionHeldSupportAccessManage() throws Exception {
        User support = supportAccount(Set.of(IdentityFixtures.ADMINISTRATOR));
        User otherSupport = supportAccount(Set.of());

        mvc.perform(grantRequest(otherSupport.id(), 60, "x").with(actor(support.id(),
                        Permission.SUPPORT_ACCESS_MANAGE.authority())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSupportUserCannotEscalateThroughAccountsOrRoles() throws Exception {
        UUID store = fixtures.store();
        User support = supportAccount(Set.of(IdentityFixtures.ADMINISTRATOR));
        Cookie session = supportSession(support, 120);

        mvc.perform(post("/api/v1/backoffice/users").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("backdoor"), "initialPassword", PASSWORD,
                                "roleIds", Set.of(IdentityFixtures.ADMINISTRATOR))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(IdentityFixtures.ADMINISTRATOR), "active", true,
                                "version", 1)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/backoffice/roles").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", unique("R"), "permissions", Set.of("SUPPORT_ACCESS_MANAGE"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/backoffice/employees").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", unique("E"), "name", "Backdoor", "pin", "4821", "storeId", store,
                                "roleId", IdentityFixtures.MANAGER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSupportFlagIsShownAndCannotBeChangedByAnUpdate() throws Exception {
        User support = supportAccount(Set.of());

        mvc.perform(get("/api/v1/backoffice/users/" + support.id()).with(admin()))
                .andExpect(jsonPath("$.support").value(true));
        mvc.perform(put("/api/v1/backoffice/users/" + support.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("roleIds", Set.of(), "active", true, "version", 0, "support", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.support").value(true));
    }

    // --- helpers ---

    /** A support account that still has its initial password {@link #PASSWORD}. */
    private User supportAccount(Set<UUID> roles) throws Exception {
        String username = unique("sup");
        UUID id = IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/users").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "initialPassword", PASSWORD, "roleIds", roles,
                                "support", true)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        return new User(id, username);
    }

    private Cookie supportSession(Set<UUID> roles) throws Exception {
        return supportSession(supportAccount(roles), 120);
    }

    /** Grants, logs in and changes the initial password, so the session holds the account's permissions. */
    private Cookie supportSession(User support, int minutes) throws Exception {
        grant(support, minutes);
        Cookie first = fixtures.login(support.username(), PASSWORD);
        return mvc.perform(post("/api/v1/auth/password").cookie(first).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("currentPassword", PASSWORD, "newPassword", IdentityFixtures.STRONG_PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    private UUID grant(User support, int minutes) throws Exception {
        return IdentityFixtures.id(mvc.perform(grantRequest(support.id(), minutes, "vendor ticket")
                        .with(asAdministrator()))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    private static MockHttpServletRequestBuilder grantRequest(UUID accountId, int minutes, String reason) {
        return grantRequest(accountId, minutes, reason, UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder grantRequest(UUID accountId, int minutes, String reason,
                                                              String idempotencyKey) {
        return post(GRANTS).with(csrf()).header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json("accountId", accountId, "durationMinutes", minutes, "reason", reason));
    }

    private static MockHttpServletRequestBuilder revoke(UUID grant) {
        return post(GRANTS + "/" + grant + "/revoke").with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private static MockHttpServletRequestBuilder createSupportAccount(String username, UUID employeeId) {
        return post("/api/v1/backoffice/users").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", username, "initialPassword", PASSWORD, "employeeId", employeeId,
                        "support", true));
    }

    /** The real administrator account, with every permission installation-wide (as its login would give). */
    private RequestPostProcessor asAdministrator() {
        return actor(administrator.id(), Arrays.stream(Permission.values()).map(Permission::authority)
                .toArray(String[]::new));
    }

    private static RequestPostProcessor actor(UUID accountId, String... authorities) {
        Actor actor = Actor.account(accountId, Set.of(authorities));
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    /** What the security chain answers a request with no session. */
    private String unauthenticatedBody() throws Exception {
        return mvc.perform(get(GRANTS)).andExpect(status().isUnauthorized()).andReturn().getResponse()
                .getContentAsString();
    }

    private long maxInactiveSeconds(Cookie session) {
        return jdbc.sql("SELECT max_inactive_interval FROM shared.spring_session WHERE session_id = :id")
                .param("id", sessionId(session)).query(Long.class).single();
    }

    private long sessionCount(User account) {
        return jdbc.sql("SELECT count(*) FROM shared.spring_session WHERE principal_name = :p")
                .param("p", "ACCOUNT:" + account.id()).query(Long.class).single();
    }

    private long auditCount(String action, UUID grant) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :a AND entity_id = :id")
                .param("a", action).param("id", grant.toString()).query(Long.class).single();
    }

    private static String sessionId(Cookie cookie) {
        return new String(java.util.Base64.getDecoder().decode(cookie.getValue()),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
