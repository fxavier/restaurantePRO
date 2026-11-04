package com.xavier.backend.identity.controller;

import static com.xavier.backend.identity.IdentityFixtures.json;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.identity.IdentityFixtures.Terminal;
import jakarta.servlet.http.Cookie;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance criterion 4 (decisions §7, spec §14 #9) with real logins, not a test actor: an employee assigned to
 * store A as Waiter gets 403 on a BackOffice catalog-type write and on store B's resources. The catalog module
 * arrives in S4, so a probe stands in for a catalog write use case (it authorizes like one).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, StoreScopeHttpTest.CatalogProbe.class})
@TestPropertySource(properties = {
        "restaurantpro.bootstrap.company.name=Test Company",
        "restaurantpro.bootstrap.company.tax-id=TEST-TAX-ID",
        "restaurantpro.bootstrap.company.country=MZ",
        "restaurantpro.bootstrap.company.currency=MZN"
})
class StoreScopeHttpTest {

    static final String PIN = "2468";

    @Autowired
    MockMvc mvc;

    IdentityFixtures fixtures;
    UUID storeA;
    UUID storeB;
    Terminal terminalA;

    @BeforeEach
    void stores() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        storeA = fixtures.store();
        storeB = fixtures.store();
        terminalA = fixtures.enrolledTerminal(storeA);
    }

    @Test
    void aWaiterLoggedInOnTheirStoresTerminalCannotWriteTheCatalogNorTouchStoreB() throws Exception {
        Employee waiter = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        Cookie session = fixtures.posLogin(terminalA, waiter, PIN);

        assertRefusedEverywhere(session);
    }

    @Test
    void aWaiterLoggedInToTheBackOfficeIsRefusedTheSame() throws Exception {
        Employee waiter = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        IdentityFixtures.User user = fixtures.user(Set.of(), waiter.id());
        Cookie session = fixtures.login(user.username(), IdentityFixtures.STRONG_PASSWORD);

        assertRefusedEverywhere(session);
    }

    @Test
    void aManagerOfStoreAWritesTheCatalogInAButNotInB() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);
        Cookie session = fixtures.posLogin(terminalA, manager, PIN);

        mvc.perform(post("/api/v1/backoffice/catalog-probe/{store}", storeA).cookie(session).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/backoffice/catalog-probe/{store}", storeB).cookie(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPosSessionHoldsOnlyTheTerminalsStoreEvenForAManagerOfBoth() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);
        mvc.perform(post("/api/v1/backoffice/employees/" + manager.id() + "/assignments")
                        .with(IdentityFixtures.admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeB, "roleId", IdentityFixtures.MANAGER)))
                .andExpect(status().isCreated());

        Cookie session = fixtures.posLogin(terminalA, manager, PIN);

        mvc.perform(post("/api/v1/backoffice/catalog-probe/{store}", storeB).cookie(session).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private void assertRefusedEverywhere(Cookie session) throws Exception {
        mvc.perform(post("/api/v1/backoffice/catalog-probe/{store}", storeA).cookie(session).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/backoffice/stores/{id}", storeB).cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "B1", "name", "B", "timezone", "Africa/Maputo",
                                "businessDayCutoff", "05:00", "active", true, "version", 0)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/backoffice/employees").param("storeId", storeB.toString()).cookie(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/backoffice/terminals").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeB, "code", "X1", "name", "X")))
                .andExpect(status().isForbidden());
    }

    /** Stands in for a catalog write use case (S4): it authorizes CATALOG_MANAGE in the store first. */
    @RestController
    static class CatalogProbe {

        private final AccessPolicy accessPolicy;

        CatalogProbe(AccessPolicy accessPolicy) {
            this.accessPolicy = accessPolicy;
        }

        @PostMapping("/api/v1/backoffice/catalog-probe/{storeId}")
        String write(@PathVariable UUID storeId) {
            accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
            return "written";
        }
    }
}
