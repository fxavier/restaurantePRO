package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.as;
import static com.xavier.backend.identity.IdentityFixtures.json;
import static com.xavier.backend.identity.IdentityFixtures.unique;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityTest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * r2-decisions §12: an account linked to an employee holds that employee's store authorities, so linking needs what
 * assigning needs: {@code EMPLOYEE_MANAGE} and every permission of the assignment's role, in each of their stores.
 */
@IdentityTest
class EmployeeLinkHttpTest {

    static final String USERS = "/api/v1/backoffice/users";
    static final String PASSWORD = "initial password 1";

    @Autowired
    MockMvc mvc;

    IdentityFixtures fixtures;
    UUID storeA;
    UUID storeB;
    UUID employee;

    /** A waiter in store A who is also a manager in store B. */
    @BeforeEach
    void employeeInTwoStores() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        storeA = fixtures.store();
        storeB = fixtures.store();
        employee = fixtures.employee("4821", storeA, IdentityFixtures.WAITER).id();
        mvc.perform(post("/api/v1/backoffice/employees/" + employee + "/assignments").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeB, "roleId", IdentityFixtures.MANAGER)))
                .andExpect(status().isCreated());
    }

    @Test
    void linkingANewAccountNeedsTheEmployeesRolesInEveryOneOfTheirStores() throws Exception {
        mvc.perform(createLinkedTo(employee).with(userManagerControlling(storeA)))
                .andExpect(status().isForbidden());

        mvc.perform(createLinkedTo(employee).with(userManagerControlling(storeA, storeB)))
                .andExpect(status().isCreated());
    }

    @Test
    void relinkingAnAccountNeedsTheSameAndKeepingTheLinkDoesNot() throws Exception {
        UUID account = IdentityFixtures.id(mvc.perform(post(USERS).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", unique("u"), "initialPassword", PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse());

        mvc.perform(updateAccount(account, employee, true, 0).with(userManagerControlling(storeA)))
                .andExpect(status().isForbidden());
        mvc.perform(updateAccount(account, employee, true, 0).with(admin())).andExpect(status().isOk());

        mvc.perform(updateAccount(account, employee, false, 1).with(userManagerControlling()))
                .andExpect(status().isOk());
    }

    /** {@code USER_MANAGE} installation-wide, and every permission only in {@code stores}. */
    private static RequestPostProcessor userManagerControlling(UUID... stores) {
        Set<String> authorities = new HashSet<>(Set.of(Permission.USER_MANAGE.authority()));
        for (UUID store : stores) {
            authorities.addAll(Arrays.stream(Permission.values()).map(p -> p.authority(store))
                    .collect(Collectors.toSet()));
        }
        return as(authorities);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createLinkedTo(
            UUID employeeId) {
        return post(USERS).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", unique("u"), "initialPassword", PASSWORD, "employeeId", employeeId));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder updateAccount(
            UUID account, UUID employeeId, boolean active, long version) {
        return put(USERS + "/" + account).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("employeeId", employeeId, "active", active, "version", version));
    }
}
