package com.xavier.backend.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Permission;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.config.security.Members;
import com.xavier.backend.config.security.SecurityHttpTest;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A84 (identity), A82, security rules 2, 3 and 5: tenants A and B with identical role names, employee codes and
 * usernames. Through the API (real tokens and resolution), B's account and employee addressed by id from A are absent
 * (404) and A's lists hold only A's rows. With no {@code AccessPolicy} in front (the application-role seam), B's rows
 * are invisible to A, a row with B's {@code tenant_id} is refused ({@code 42501}), a row never moves tenant
 * ({@code RS002}), a child cannot reference B's parent (composite foreign key), and an unbound read raises
 * {@code RS003}.
 */
@SecurityHttpTest
class IdentityTenantIsolationTest {

    static UUID a;
    static UUID b;
    static String adminOfA;
    static UUID anaOfA;
    static UUID anaOfB;
    static UUID roleOfB;
    static UUID employeeOfA;
    static UUID employeeOfB;

    @Autowired
    MockMvc mvc;

    @BeforeAll
    static void twoTenantsWithIdenticalCodes() {
        a = Members.activeTenant();
        b = Members.activeTenant();
        adminOfA = "kc-" + UUID.randomUUID();
        Members.member(a, adminOfA, Members.role(a, Permission.USER_MANAGE, Permission.EMPLOYEE_MANAGE));
        anaOfA = seed(a);
        anaOfB = seed(b);
        roleOfB = OwnerDatabase.shared().jdbc().sql("SELECT id FROM identity.role WHERE tenant_id = ? AND name = 'Gerente'")
                .param(b).query(UUID.class).single();
        employeeOfA = employee(a);
        employeeOfB = employee(b);
    }

    /** Role "Gerente", employee "E-ISO" and account "ana" in {@code tenant}, as its application role writes them. */
    private static UUID seed(UUID tenant) {
        UUID role = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            org.springframework.jdbc.core.simple.JdbcClient jdbc = app.jdbc();
            {
                jdbc.sql("INSERT INTO identity.role (id, name, version, created_at, updated_at) "
                        + "VALUES (?, 'Gerente', 0, now(), now())").param(role).update();
                jdbc.sql("INSERT INTO identity.employee (id, code, name, pin_hash, failed_pin_attempts, locked, "
                        + "active, version, created_at, updated_at) VALUES (?, 'E-ISO', 'Ana', "
                        + "'{bcrypt}$2a$10$abcdefghijklmnopqrstuuabcdefghijklmnopqrstuvwxyz012', 0, false, true, 0, "
                        + "now(), now())").param(employee).update();
                jdbc.sql("INSERT INTO identity.user_account (id, username, active, support, employee_id, version, "
                        + "created_at, updated_at) VALUES (?, 'ana', true, false, ?, 0, now(), now())")
                        .param(account).param(employee).update();
                jdbc.sql("INSERT INTO identity.user_account_role (user_account_id, role_id) VALUES (?, ?)")
                        .param(account).param(role).update();
            }
        }
        return account;
    }

    private static UUID employee(UUID tenant) {
        return OwnerDatabase.shared().jdbc().sql("SELECT id FROM identity.employee WHERE tenant_id = ? "
                + "AND code = 'E-ISO'").param(tenant).query(UUID.class).single();
    }

    private static RequestPostProcessor admin() {
        return TestJwtFactory.account(adminOfA).bearer();
    }

    @Test
    void bothTenantsUseTheSameCodes() {
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT u.tenant_id FROM identity.user_account u "
                        + "JOIN identity.employee e ON e.id = u.employee_id AND e.code = 'E-ISO' WHERE u.username = 'ana'")
                .query(UUID.class).list()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void anotherTenantsAccountAndEmployeeById404() throws Exception {
        mvc.perform(get("/api/v1/backoffice/users/" + anaOfB).with(admin())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/users/" + anaOfA).with(admin())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/backoffice/employees/" + employeeOfB).with(admin())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/backoffice/employees/" + employeeOfA).with(admin())).andExpect(status().isOk());
    }

    @Test
    void aTenantsListHoldsOnlyItsOwnAccounts() throws Exception {
        mvc.perform(get("/api/v1/backoffice/users?size=200").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + anaOfB + "')]").isEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + anaOfA + "')]").isNotEmpty());
    }

    @Test
    void aTenantSeesOnlyItsOwnRows() {
        try (AppRoleSession app = AppRoleSession.open().tenant(a).allStores()) {
            assertThat(app.jdbc().sql("SELECT id FROM identity.user_account WHERE username = 'ana'")
                    .query(UUID.class).list()).containsExactly(anaOfA);
            assertThat(app.jdbc().sql("SELECT count(*) FROM identity.role WHERE id = ?")
                    .param(roleOfB).query(Long.class).single()).isZero();
        }
    }

    @Test
    void aRowWithAnotherTenantsIdIsRefused() {
        try (AppRoleSession app = AppRoleSession.open().tenant(a).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("INSERT INTO identity.role "
                    + "(id, tenant_id, name, version, created_at, updated_at) VALUES (?, ?, 'Forged', 0, now(), now())")
                    .param(UUID.randomUUID()).param(b).update(), StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        }
    }

    @Test
    void aRowNeverMovesToAnotherTenant() {
        try (AppRoleSession app = AppRoleSession.open().tenant(a).allStores()) {
            AppRoleSession.assertRefused(() -> app.jdbc().sql("UPDATE identity.user_account "
                    + "SET tenant_id = ? WHERE id = ?").param(b).param(anaOfA).update(),
                    StoreScopeViolations.Kind.STORE_CHANGE);
        }
    }

    @Test
    void aChildCannotReferenceAnotherTenantsParent() {
        try (AppRoleSession app = AppRoleSession.open().tenant(a).allStores()) {
            assertThatThrownBy(() -> app.jdbc().sql("INSERT INTO identity.user_account_role "
                    + "(user_account_id, role_id) VALUES (?, ?)").param(anaOfA).param(roleOfB).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(error -> assertThat(AppRoleSession.sqlState(error)).isEqualTo("23503"));
        }
    }

    @Test
    void anUnboundReadFailsClosed() {
        try (AppRoleSession app = AppRoleSession.open().noTenant().allStores()) {
            for (String table : List.of("role", "role_permission", "employee", "employee_store_assignment",
                    "user_account", "user_account_role", "terminal_session", "support_grant")) {
                AppRoleSession.assertRefused(() -> app.jdbc().sql("SELECT count(*) FROM identity."
                        + table).query(Long.class).single(), StoreScopeViolations.Kind.TENANT_MISSING);
            }
        }
    }

    @Test
    void theModuleIsTenantProtected() {
        RowLevelSecurityCoverageTest.assertModuleTenantProtected("identity");
    }
}
