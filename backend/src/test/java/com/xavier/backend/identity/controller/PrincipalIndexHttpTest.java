package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.Terminal;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D38: sessions are indexed by actor kind and principal id, so an account and an employee that happen to share a
 * UUID never see or end each other's sessions.
 */
@IdentityTest
class PrincipalIndexHttpTest {

    static final String PIN = "4821";
    static final String PASSWORD = "collision password 1";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Test
    void anAccountWithAnEmployeesIdOwnsOnlyItsOwnSessions() throws Exception {
        IdentityFixtures fixtures = new IdentityFixtures(mvc);
        UUID store = fixtures.store();
        Terminal terminal = fixtures.enrolledTerminal(store);
        Employee employee = fixtures.employee(PIN, store, IdentityFixtures.WAITER);
        String username = accountWithId(employee.id());
        Cookie pos = fixtures.posLogin(terminal, employee, PIN);
        Cookie backOffice = fixtures.login(username, PASSWORD);

        mvc.perform(get("/api/v1/backoffice/users/" + employee.id() + "/sessions").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(put("/api/v1/backoffice/users/" + employee.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("active", false, "version", 0)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/auth/session").cookie(backOffice)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").cookie(pos)).andExpect(status().isOk());
    }

    /** An account whose id is {@code id}: the API never picks ids, so the collision is written directly. */
    private String accountWithId(UUID id) {
        String username = IdentityFixtures.unique("collide");
        jdbc.sql("""
                        INSERT INTO identity.user_account (id, username, password_hash, must_change_password, active,
                                                           employee_id, version, created_at, updated_at)
                        VALUES (:id, :username, :hash, false, true, NULL, 0, :now, :now)""")
                .param("id", id).param("username", username).param("hash", encoder.encode(PASSWORD))
                .param("now", java.sql.Timestamp.from(Instant.now()))
                .update();
        return username;
    }
}
