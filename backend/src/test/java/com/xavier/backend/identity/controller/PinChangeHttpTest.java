package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static com.xavier.backend.identity.IdentityFixtures.posLoginRequest;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.Terminal;
import com.xavier.backend.identity.IdentityTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * r2-decisions §12: a PIN set by a manager ({@code reset-pin}) is known to that manager, so the employee's next POS
 * session holds nothing until the employee sets a PIN of their own.
 */
@IdentityTest
class PinChangeHttpTest {

    static final String PIN = "4821";
    static final String RESET_PIN = "135790";
    static final String OWN_PIN = "246810";
    static final String POS_ROUTE = "/api/v1/pos/notifications";

    @Autowired
    MockMvc mvc;

    @Autowired
    ApproverVerifier approvers;

    IdentityFixtures fixtures;
    UUID store;
    Terminal terminal;

    @BeforeEach
    void terminal() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        store = fixtures.store();
        terminal = fixtures.enrolledTerminal(store);
    }

    @Test
    void afterAResetThePosSessionHoldsNothingUntilTheEmployeeSetsTheirOwnPin() throws Exception {
        Employee employee = resetEmployee(IdentityFixtures.WAITER);

        Cookie session = mvc.perform(posLoginRequest(terminal.token(), employee.code(), RESET_PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePin").value(true))
                .andExpect(jsonPath("$.permissions", empty()))
                .andReturn().getResponse().getCookie("SESSION");
        mvc.perform(get(POS_ROUTE).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("change your PIN")));

        MvcResult changed = mvc.perform(changePin(session, OWN_PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePin").value(false))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(IdentityFixtures.waiterAuthorities(store))))
                .andReturn();
        Cookie renewed = changed.getResponse().getCookie("SESSION");

        mvc.perform(get(POS_ROUTE).cookie(renewed == null ? session : renewed)).andExpect(status().isOk());
        mvc.perform(posLoginRequest(terminal.token(), employee.code(), RESET_PIN)).andExpect(status().isUnauthorized());
        mvc.perform(posLoginRequest(terminal.token(), employee.code(), OWN_PIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePin").value(false))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder(IdentityFixtures.waiterAuthorities(store))));
    }

    @Test
    void theNewPinMustDifferFromTheOneTheManagerSet() throws Exception {
        Employee employee = resetEmployee(IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminal, employee, RESET_PIN);

        mvc.perform(changePin(session, RESET_PIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PIN_UNCHANGED"));
    }

    @Test
    void onlyASessionThatMustChangeItsPinUsesTheRoute() throws Exception {
        Employee employee = fixtures.employee(PIN, store, IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminal, employee, PIN);

        mvc.perform(changePin(session, OWN_PIN))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PIN_CHANGE_NOT_REQUIRED"));
    }

    @Test
    void aNewPinMustBeFourToEightDigits() throws Exception {
        Cookie session = fixtures.posLogin(terminal, resetEmployee(IdentityFixtures.WAITER), RESET_PIN);

        mvc.perform(changePin(session, "12a4")).andExpect(status().isBadRequest());
    }

    @Test
    void aPinTheManagerSetDoesNotApprove() throws Exception {
        Employee manager = resetEmployee(IdentityFixtures.MANAGER);

        assertThatThrownBy(() -> approvers.verify(manager.code(), RESET_PIN, Permission.CATALOG_PUBLISH, store))
                .isInstanceOf(ApprovalDeniedException.class);
    }

    private Employee resetEmployee(UUID role) throws Exception {
        Employee employee = fixtures.employee(PIN, store, role);
        mvc.perform(post("/api/v1/backoffice/employees/" + employee.id() + "/reset-pin").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\": \"%s\"}".formatted(RESET_PIN)))
                .andExpect(status().isOk());
        return employee;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder changePin(
            Cookie session, String newPin) {
        return post("/api/v1/auth/pos-pin").cookie(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(IdentityFixtures.json("newPin", newPin));
    }
}
