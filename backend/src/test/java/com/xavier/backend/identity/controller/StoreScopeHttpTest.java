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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acceptance criterion 4 (decisions §7, spec §14 #9) with real logins, not a test actor: an employee assigned to
 * store A as Waiter gets 403 on a catalog-type write and on store B's resources. A POS session holds only its
 * terminal's store, and it is no BackOffice credential at all (D176, D182: 401 on every BackOffice route). Probes
 * stand in for a catalog write use case (they authorize like one), on the BackOffice and on the POS routes.
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
    static final String BACKOFFICE_PROBE = "/api/v1/backoffice/catalog-probe/{store}";
    static final String POS_PROBE = "/api/v1/pos/catalog-probe/{store}";

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
        RequestPostProcessor session = fixtures.posLogin(terminalA, waiter, PIN);

        mvc.perform(post(POS_PROBE, storeA).with(session)).andExpect(status().isForbidden());
        mvc.perform(post(POS_PROBE, storeB).with(session)).andExpect(status().isForbidden());
        assertRefusedEverywhere(session, 401);
    }

    @Test
    void aWaiterLoggedInToTheBackOfficeIsRefusedTheSame() throws Exception {
        Employee waiter = fixtures.employee(PIN, storeA, IdentityFixtures.WAITER);
        IdentityFixtures.User user = fixtures.user(Set.of(), waiter.id());
        RequestPostProcessor session = IdentityFixtures.login(user);

        assertRefusedEverywhere(session, 403);
    }

    @Test
    void aManagerOfStoreAWritesTheCatalogInAButNotInB() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);
        RequestPostProcessor session = fixtures.posLogin(terminalA, manager, PIN);

        mvc.perform(post(POS_PROBE, storeA).with(session)).andExpect(status().isOk());
        mvc.perform(post(POS_PROBE, storeB).with(session)).andExpect(status().isForbidden());
        mvc.perform(post(BACKOFFICE_PROBE, storeA).with(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void aPosSessionHoldsOnlyTheTerminalsStoreEvenForAManagerOfBoth() throws Exception {
        Employee manager = fixtures.employee(PIN, storeA, IdentityFixtures.MANAGER);
        mvc.perform(post("/api/v1/backoffice/employees/" + manager.id() + "/assignments")
                        .with(IdentityFixtures.admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeB, "roleId", IdentityFixtures.MANAGER)))
                .andExpect(status().isCreated());

        RequestPostProcessor session = fixtures.posLogin(terminalA, manager, PIN);

        mvc.perform(post(POS_PROBE, storeB).with(session)).andExpect(status().isForbidden());
    }

    /** Every BackOffice write and read of store B, and the catalog write in A, answer {@code refusal}. */
    private void assertRefusedEverywhere(RequestPostProcessor session, int refusal) throws Exception {
        mvc.perform(post(BACKOFFICE_PROBE, storeA).with(session)).andExpect(status().is(refusal));
        mvc.perform(put("/api/v1/backoffice/stores/{id}", storeB).with(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "B1", "name", "B", "timezone", "Africa/Maputo",
                                "businessDayCutoff", "05:00", "active", true, "version", 0)))
                .andExpect(status().is(refusal));
        mvc.perform(get("/api/v1/backoffice/employees").param("storeId", storeB.toString()).with(session))
                .andExpect(status().is(refusal));
        mvc.perform(post("/api/v1/backoffice/terminals").with(session).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("storeId", storeB, "code", "X1", "name", "X")))
                .andExpect(status().is(refusal));
    }

    /** Stands in for a catalog write use case (S4): it authorizes CATALOG_MANAGE in the store first. */
    @RestController
    static class CatalogProbe {

        private final AccessPolicy accessPolicy;

        CatalogProbe(AccessPolicy accessPolicy) {
            this.accessPolicy = accessPolicy;
        }

        @PostMapping({"/api/v1/backoffice/catalog-probe/{storeId}", "/api/v1/pos/catalog-probe/{storeId}"})
        String write(@PathVariable UUID storeId) {
            accessPolicy.require(Permission.CATALOG_MANAGE, storeId);
            return "written";
        }
    }
}
