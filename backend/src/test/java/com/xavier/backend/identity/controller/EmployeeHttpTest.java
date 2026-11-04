package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityTest;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@IdentityTest
class EmployeeHttpTest {

    static final String EMPLOYEES = "/api/v1/backoffice/employees";
    static final Set<Permission> MANAGER_PERMISSIONS = Set.of(Permission.TERMINAL_MANAGE, Permission.EMPLOYEE_MANAGE,
            Permission.DINING_MANAGE, Permission.DINING_VIEW, Permission.CATALOG_MANAGE, Permission.CATALOG_PUBLISH,
            Permission.CATALOG_VIEW, Permission.AUDIT_VIEW);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    IdentityFixtures fixtures;
    UUID storeA;
    UUID storeB;

    @BeforeEach
    void stores() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        storeA = fixtures.store();
        storeB = fixtures.store();
    }

    @Test
    void aStoreManagerHiresIntoTheirStoreOnlyAndOnlyHashesOfThePinAreStored() throws Exception {
        String code = unique("E");

        UUID id = IdentityFixtures.id(mvc.perform(hire(managerOf(storeA), code, "4821", storeA, IdentityFixtures.WAITER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.locked").value(false))
                .andReturn().getResponse());
        mvc.perform(hire(managerOf(storeA), unique("E"), "4821", storeB, IdentityFixtures.WAITER))
                .andExpect(status().isForbidden());

        assertThat(jdbc.sql("SELECT pin_hash FROM identity.employee WHERE id = :id").param("id", id)
                .query(String.class).single()).startsWith("{bcrypt}").doesNotContain("4821");
        assertThat(jdbc.sql("""
                        SELECT action FROM audit.audit_event WHERE entity_id = :id ORDER BY occurred_at, id""")
                .param("id", id.toString()).query(String.class).list())
                .containsExactlyInAnyOrder("EMPLOYEE_HIRED", "EMPLOYEE_ASSIGNED");
        assertThat(jdbc.sql("SELECT details::text FROM audit.audit_event WHERE entity_id = :id")
                .param("id", id.toString()).query(String.class).list()).noneMatch(d -> d.contains("4821"));
    }

    @Test
    void nobodyHandsOutAPermissionTheyDoNotHoldInThatStore() throws Exception {
        mvc.perform(hire(managerOf(storeA), unique("E"), "4821", storeA, IdentityFixtures.ADMINISTRATOR))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPinIsFourToEightDigits() throws Exception {
        for (String pin : List.of("123", "123456789", "12a4")) {
            mvc.perform(hire(admin(), unique("E"), pin, storeA, IdentityFixtures.WAITER))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("pin"));
        }
    }

    @Test
    void anEmployeeCodeIsUniqueEvenUnderARace() throws Exception {
        String code = unique("E");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return mvc.perform(hire(admin(), code, "4821", storeA, IdentityFixtures.WAITER))
                    .andReturn().getResponse().getStatus();
        };

        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();
            statuses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        mvc.perform(hire(admin(), code, "4821", storeA, IdentityFixtures.WAITER)).andExpect(status().isConflict());
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        Employee employee = fixtures.employee("4821", storeA, IdentityFixtures.WAITER);
        mvc.perform(put(EMPLOYEES + "/" + employee.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("name", "Ana B", "active", true,
                                "version", 0)))
                .andExpect(status().isOk());

        mvc.perform(put(EMPLOYEES + "/" + employee.id()).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json("name", "Ana C", "active", true,
                                "version", 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void aManagerOfAnotherStoreCannotTouchTheEmployee() throws Exception {
        Employee employee = fixtures.employee("4821", storeA, IdentityFixtures.WAITER);

        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/unlock").with(managerOf(storeB)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/reset-pin").with(managerOf(storeB)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"1111\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(EMPLOYEES + "/" + employee.id()).with(managerOf(storeA))).andExpect(status().isOk());
    }

    @Test
    void aManagerCannotTakeOverAnEmployeeOfAnotherStore() throws Exception {
        // E is a manager in store B only; M manages store A
        Employee employee = fixtures.employee("4821", storeB, IdentityFixtures.MANAGER);

        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/assignments").with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeA, "roleId", IdentityFixtures.WAITER)))
                .andExpect(status().isForbidden());

        // even once E also works in A, M cannot change what E can do in B
        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/assignments").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeA, "roleId", IdentityFixtures.WAITER)))
                .andExpect(status().isCreated());
        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/reset-pin").with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"1111\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/unlock").with(managerOf(storeA)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put(EMPLOYEES + "/" + employee.id()).with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Taken", "active", false, "version", 0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerOfEveryStoreOfTheEmployeeResetsTheirPin() throws Exception {
        Employee employee = fixtures.employee("4821", storeA, IdentityFixtures.WAITER);

        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/reset-pin").with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"1111\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void assignmentsAddAndRemoveStoresAndRolesAndAreAudited() throws Exception {
        Employee employee = fixtures.employee("4821", storeA, IdentityFixtures.WAITER);
        String assignment = json("storeId", storeB, "roleId", IdentityFixtures.WAITER);

        UUID added = IdentityFixtures.id(mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/assignments")
                        .with(managerOf(storeA, storeB)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(assignment))
                .andExpect(status().isCreated()).andReturn().getResponse());
        mvc.perform(post(EMPLOYEES + "/" + employee.id() + "/assignments").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(assignment))
                .andExpect(status().isConflict());
        mvc.perform(get(EMPLOYEES + "/" + employee.id() + "/assignments").with(admin()))
                .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(delete(EMPLOYEES + "/" + employee.id() + "/assignments/" + added).with(managerOf(storeA))
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(EMPLOYEES + "/" + employee.id() + "/assignments/" + added).with(managerOf(storeB))
                        .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event WHERE entity_id = :id AND store_id = :store
                          AND action IN ('EMPLOYEE_ASSIGNED', 'EMPLOYEE_UNASSIGNED')""")
                .param("id", employee.id().toString()).param("store", storeB).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void anUnknownStoreOrRoleIsNotFound() throws Exception {
        mvc.perform(hire(admin(), unique("E"), "4821", UUID.randomUUID(), IdentityFixtures.WAITER))
                .andExpect(status().isNotFound());
        mvc.perform(hire(admin(), unique("E"), "4821", storeA, UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aStoreListsItsEmployees() throws Exception {
        Employee inA = fixtures.employee("4821", storeA, IdentityFixtures.WAITER);
        fixtures.employee("4821", storeB, IdentityFixtures.WAITER);

        mvc.perform(get(EMPLOYEES).param("storeId", storeA.toString()).with(managerOf(storeA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(inA.id().toString()));
        mvc.perform(get(EMPLOYEES).with(managerOf(storeA))).andExpect(status().isForbidden());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void sortingAStoresEmployeesByAnUnknownPropertyIsABadRequestNotAnError(CapturedOutput output) throws Exception {
        mvc.perform(get(EMPLOYEES).param("storeId", storeA.toString()).param("sort", "nope").with(managerOf(storeA)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown sort property."));
        assertThat(output.getAll()).doesNotContain("Unhandled exception");
    }

    @Test
    void aSortTokenPaddedWithWhitespaceIsABadRequest() throws Exception {
        for (String sort : List.of("code ", "code,desc ")) {
            mvc.perform(get(EMPLOYEES).param("storeId", storeA.toString()).param("sort", sort).with(managerOf(storeA)))
                    .andExpect(status().isBadRequest());
        }
    }

    private static RequestPostProcessor managerOf(UUID... stores) {
        return as(java.util.Arrays.stream(stores).flatMap(store -> MANAGER_PERMISSIONS.stream()
                .map(p -> p.authority(store))).collect(Collectors.toSet()));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder hire(
            RequestPostProcessor actor, String code, String pin, UUID store, UUID role) {
        return post(EMPLOYEES).with(actor).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("code", code, "name", "Ana", "pin", pin, "storeId", store, "roleId", role));
    }
}
