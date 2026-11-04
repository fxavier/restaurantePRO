package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.posLoginRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.Terminal;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IdentityTest
@ExtendWith(OutputCaptureExtension.class)
class PosLoginHttpTest {

    static final String PIN = "4821";
    static final String WRONG_PIN = "9999";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    IdentityFixtures fixtures;
    UUID storeA;
    Terminal terminalA;

    @BeforeEach
    void storeWithTerminal() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        storeA = fixtures.store();
        terminalA = fixtures.enrolledTerminal(storeA);
    }

    @Test
    void aPinLoginOnAnEnrolledTerminalGrantsOnlyTheAuthoritiesOfTheTerminalsStore() throws Exception {
        UUID storeB = fixtures.store();
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        mvc.perform(post("/api/v1/backoffice/employees/" + employee.id() + "/assignments").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("storeId", storeB, "roleId", IdentityFixtures.MANAGER)))
                .andExpect(status().isCreated());

        Cookie session = fixtures.posLogin(terminalA, employee, PIN);

        mvc.perform(get("/api/v1/auth/session").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(employee.id().toString()))
                .andExpect(jsonPath("$.terminalId").value(terminalA.id().toString()))
                .andExpect(jsonPath("$.storeId").value(storeA.toString()))
                .andExpect(jsonPath("$.name").value("Ana"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(
                        Permission.DINING_VIEW.authority(storeA), Permission.CATALOG_VIEW.authority(storeA))));
    }

    @Test
    void aPinLoginNeedsAnEnrolledActiveTerminal() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);

        mvc.perform(posLoginRequest(null, employee.code(), PIN)).andExpect(status().isUnauthorized());
        mvc.perform(posLoginRequest("not-a-device-token", employee.code(), PIN)).andExpect(status().isUnauthorized());
        fixtures.revoke(terminalA);
        mvc.perform(posLoginRequest(terminalA.token(), employee.code(), PIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void anEmployeeNotAssignedToTheTerminalsStoreIsRefused() throws Exception {
        Employee elsewhere = fixtures.employee(PIN, fixtures.store(), IdentityFixtures.WAITER);

        mvc.perform(posLoginRequest(terminalA.token(), elsewhere.code(), PIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void anInactiveStoreRefusesPinLogins() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        fixtures.deactivateStore(storeA);

        mvc.perform(posLoginRequest(terminalA.token(), employee.code(), PIN)).andExpect(status().isUnauthorized());
    }

    @Test
    void theFifthWrongPinLocksTheEmployeeAndTheLockIsAudited(CapturedOutput output) throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);

        for (int i = 0; i < 5; i++) {
            mvc.perform(posLoginRequest(terminalA.token(), employee.code(), WRONG_PIN))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(locked(employee)).isTrue();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE action = 'EMPLOYEE_LOCKED' AND entity_id = :id
                          AND terminal_id = :terminal AND store_id = :store""")
                .param("id", employee.id().toString()).param("terminal", terminalA.id()).param("store", storeA)
                .query(Long.class).single()).isEqualTo(1);
        // locked: even the right PIN is refused
        mvc.perform(posLoginRequest(terminalA.token(), employee.code(), PIN)).andExpect(status().isUnauthorized());
        assertThat(output.getAll()).contains("POS login refused").doesNotContain("\"" + WRONG_PIN + "\"")
                .doesNotContain("pin=" + WRONG_PIN);
    }

    @Test
    void aCorrectPinResetsTheFailureCount() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        for (int i = 0; i < 4; i++) {
            mvc.perform(posLoginRequest(terminalA.token(), employee.code(), WRONG_PIN));
        }

        fixtures.posLogin(terminalA, employee, PIN);
        mvc.perform(posLoginRequest(terminalA.token(), employee.code(), WRONG_PIN));

        assertThat(locked(employee)).isFalse();
        assertThat(failedAttempts(employee)).isEqualTo(1);
    }

    @Test
    void aManagerUnlocksALockedEmployee() throws Exception {
        Employee employee = lockedEmployee();

        mvc.perform(post("/api/v1/backoffice/employees/" + employee.id() + "/unlock")
                        .with(IdentityFixtures.as(java.util.Set.of(Permission.EMPLOYEE_MANAGE.authority(storeA),
                                Permission.DINING_VIEW.authority(storeA), Permission.CATALOG_VIEW.authority(storeA))))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));

        fixtures.posLogin(terminalA, employee, PIN);
        assertThat(audits("EMPLOYEE_UNLOCKED", employee)).isEqualTo(1);
    }

    @Test
    void aNewPinReplacesTheOldOneAndUnlocks() throws Exception {
        Employee employee = lockedEmployee();

        mvc.perform(post("/api/v1/backoffice/employees/" + employee.id() + "/reset-pin").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"135790\"}"))
                .andExpect(status().isOk());

        mvc.perform(posLoginRequest(terminalA.token(), employee.code(), PIN)).andExpect(status().isUnauthorized());
        fixtures.posLogin(terminalA, employee, "135790");
        assertThat(audits("EMPLOYEE_PIN_RESET", employee)).isEqualTo(1);
    }

    @Test
    void concurrentWrongPinsAreAllCounted() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(posLoginRequest(terminalA.token(), employee.code(), WRONG_PIN))
                    .andReturn().getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            List<Future<Integer>> futures = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> pool.submit(attempt)).toList();
            start.countDown();
            statuses = new java.util.ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(statuses).containsOnly(401);
        assertThat(locked(employee)).as("all five failures counted").isTrue();
    }

    @Test
    void revokingTheTerminalEndsItsPosSessions() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminalA, employee, PIN);
        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isOk());

        fixtures.revoke(terminalA);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized()));
    }

    @Test
    void deactivatingTheEmployeeEndsTheirPosSessions() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminalA, employee, PIN);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/backoffice/employees/" + employee.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("name", "Ana", "active", false, "version",
                                currentVersion(employee))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
    }

    private long currentVersion(Employee employee) {
        return jdbc.sql("SELECT version FROM identity.employee WHERE id = :id").param("id", employee.id())
                .query(Long.class).single();
    }

    @Test
    void logoutOnTheTerminalEndsThePosSession() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminalA, employee, PIN);

        mvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/auth/session").cookie(session)).andExpect(status().isUnauthorized());
    }

    private Employee lockedEmployee() throws Exception {
        Employee employee = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        for (int i = 0; i < 5; i++) {
            mvc.perform(posLoginRequest(terminalA.token(), employee.code(), WRONG_PIN));
        }
        assertThat(locked(employee)).isTrue();
        return employee;
    }

    private boolean locked(Employee employee) {
        return jdbc.sql("SELECT locked FROM identity.employee WHERE id = :id").param("id", employee.id())
                .query(Boolean.class).single();
    }

    private int failedAttempts(Employee employee) {
        return jdbc.sql("SELECT failed_pin_attempts FROM identity.employee WHERE id = :id")
                .param("id", employee.id()).query(Integer.class).single();
    }

    private long audits(String action, Employee employee) {
        return jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = :action AND entity_id = :id")
                .param("action", action).param("id", employee.id().toString()).query(Long.class).single();
    }
}
