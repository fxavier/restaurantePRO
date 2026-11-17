package com.xavier.backend.production.controller;

import static com.xavier.backend.identity.IdentityFixtures.admin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** Print jobs and print bridges for the bridge suites (D187), through the API and, for a second tenant, as the owner. */
final class BridgeJobs {

    static final String JOBS = "/api/v1/bridge/jobs";

    private final MockMvc mvc;

    BridgeJobs(MockMvc mvc) {
        this.mvc = mvc;
    }

    /** A printer of {@code store} on the LAN address {@code host:port}. */
    UUID printer(UUID store, String host, int port) throws Exception {
        String code = "P" + UUID.randomUUID().toString().substring(0, 6);
        return IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/printers").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "code": "%s", "name": "Impressora", "host": "%s", "port": %d,
                                 "codePage": "CP860"}""".formatted(store, code, host, port)))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    /** A test page queued on {@code printer} (D156): a QUEUED job of its store. */
    UUID queuedJob(UUID printer) throws Exception {
        return IdentityFixtures.id(mvc.perform(post("/api/v1/backoffice/printers/" + printer + "/test")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(admin()))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    ResultActions claim(RequestPostProcessor bridge) throws Exception {
        return mvc.perform(get(JOBS).with(bridge));
    }

    JsonNode claimed(RequestPostProcessor bridge) throws Exception {
        return IdentityFixtures.JSON.readTree(claim(bridge).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    ResultActions outcome(RequestPostProcessor bridge, UUID job, String outcome) throws Exception {
        return mvc.perform(post(JOBS + "/" + job + "/outcome").with(bridge).contentType(MediaType.APPLICATION_JSON)
                .content(IdentityFixtures.json("outcome", outcome, "paperNearEnd", false)));
    }

    static Map<String, Object> job(UUID job) {
        return OwnerDatabase.shared().jdbc().sql("SELECT * FROM production.print_job WHERE id = ?").param(job).query()
                .singleRow();
    }

    /**
     * An enrolled print bridge of a store of another ACTIVE tenant, as its enrolment leaves it (written as the owner):
     * the store, the bridge, its {@code BRIDGE} membership. @return the bridge's token
     */
    static RequestPostProcessor bridgeOfAnotherTenant(UUID tenant) {
        UUID store = UUID.randomUUID();
        UUID bridge = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        String clientId = "bridge-" + bridge;
        OwnerDatabase.shared().tx(jdbc -> {
            jdbc.sql("INSERT INTO organization.store (id, tenant_id, code, name, timezone, active, version, created_at, "
                    + "updated_at) VALUES (?, ?, 'S1', 'Loja', 'Africa/Maputo', true, 0, now(), now())")
                    .param(store).param(tenant).update();
            jdbc.sql("INSERT INTO tenancy.tenant_membership (id, tenant_id, issuer, subject, kind, principal_id, "
                            + "status, valid_from, created_at) VALUES (?, ?, ?, ?, 'BRIDGE', ?, 'ACTIVE', "
                            + "now() - interval '1 minute', now())")
                    .param(membership).param(tenant).param(TestJwtFactory.DEVICES_ISSUER)
                    .param(IdentityFixtures.Terminal.subjectOf(clientId)).param(bridge).update();
            jdbc.sql("INSERT INTO organization.terminal (id, tenant_id, store_id, kind, code, name, status, "
                            + "membership_id, version, created_at, updated_at) VALUES (?, ?, ?, 'PRINT_BRIDGE', 'PB1', "
                            + "'Bridge', 'ACTIVE', ?, 0, now(), now())")
                    .param(bridge).param(tenant).param(store).param(membership).update();
        });
        return TestJwtFactory.device(clientId, IdentityFixtures.Terminal.subjectOf(clientId)).bearer();
    }
}
