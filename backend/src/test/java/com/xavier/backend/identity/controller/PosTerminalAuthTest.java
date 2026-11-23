package com.xavier.backend.identity.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.DeviceHttpTest;
import com.xavier.backend.config.security.Devices;
import com.xavier.backend.config.security.FakeDeviceProvider;
import com.xavier.backend.config.security.Devices.Device;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.identity.IdentityFixtures.Employee;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * D182 / A86: a terminal enrolled with a one-time code authenticates with its own client's token (devices realm,
 * {@code azp pos-<id>}); the employee logs in with code + PIN on top of that token, and every POS request then carries
 * both the terminal's bearer and {@code X-Pos-Session}. The session must belong to the terminal of the bearer's
 * membership on every request (hand-off b), the POS routes accept no other realm or client, and a terminal token never
 * reaches the BackOffice.
 */
@DeviceHttpTest
class PosTerminalAuthTest {

    static final String PIN = "4821";
    static final String POS_SESSION = "/api/v1/auth/pos-session";

    @Autowired
    MockMvc mvc;

    IdentityFixtures fixtures;
    Devices devices;
    UUID store;
    Device terminal;
    Employee waiter;

    @BeforeEach
    void enrolledTerminalAndAWaiter() throws Exception {
        fixtures = new IdentityFixtures(mvc);
        devices = new Devices(mvc);
        store = fixtures.store();
        terminal = devices.enrolledTerminal(store);
        waiter = fixtures.employee(PIN, store, IdentityFixtures.WAITER);
    }

    @Test
    void anEnrolledTerminalLogsAnEmployeeInAndTheSessionWorksWithTheTerminalsToken() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(terminal.bearer()).with(posSession(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employeeId").value(waiter.id().toString()))
                .andExpect(jsonPath("$.terminalId").value(terminal.id().toString()))
                .andExpect(jsonPath("$.storeId").value(store.toString()));
    }

    @Test
    void aPosSessionNamesItsTerminalsTenant() throws Exception {
        String session = login(terminal);
        String legalName = OwnerDatabase.shared().jdbc().sql("SELECT legal_name FROM tenancy.tenant WHERE id = ?")
                .param(TenantTestSupport.BOOTSTRAP).query(String.class).single();

        mvc.perform(get(POS_SESSION).with(terminal.bearer()).with(posSession(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TenantTestSupport.BOOTSTRAP.toString()))
                .andExpect(jsonPath("$.tenantName").value(legalName));
    }

    @Test
    void aPosSessionNamesItsStoreWithItsTimezoneAndCurrency() throws Exception {
        UUID lisbon = fixtures.store("Loja Baixa", "Europe/Lisbon");
        Device lisbonTerminal = devices.enrolledTerminal(lisbon);
        mvc.perform(post("/api/v1/backoffice/employees/" + waiter.id() + "/assignments")
                        .with(IdentityFixtures.admin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("storeId", lisbon, "roleId", IdentityFixtures.WAITER)))
                .andExpect(status().isCreated());
        String currency = OwnerDatabase.shared().jdbc()
                .sql("SELECT currency FROM organization.company_profile WHERE tenant_id = ?")
                .param(TenantTestSupport.BOOTSTRAP).query(String.class).single();

        String login = mvc.perform(loginRequest().with(lisbonTerminal.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeName").value("Loja Baixa"))
                .andExpect(jsonPath("$.storeTimezone").value("Europe/Lisbon"))
                .andExpect(jsonPath("$.currency").value(currency))
                .andReturn().getResponse().getContentAsString();
        String session = IdentityFixtures.JSON.readTree(login).get("posSession").asString();

        mvc.perform(get(POS_SESSION).with(lisbonTerminal.bearer()).with(posSession(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(lisbon.toString()))
                .andExpect(jsonPath("$.storeName").value("Loja Baixa"))
                .andExpect(jsonPath("$.storeTimezone").value("Europe/Lisbon"))
                .andExpect(jsonPath("$.currency").value(currency));
    }

    /** The status bar names the terminal (light reference: "Loja Baixa · POS-02 · Ana M."). */
    @Test
    void aPosSessionNamesItsTerminal() throws Exception {
        String code = OwnerDatabase.shared().jdbc().sql("SELECT code FROM organization.terminal WHERE id = ?")
                .param(terminal.id()).query(String.class).single();

        String login = mvc.perform(loginRequest().with(terminal.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalCode").value(code))
                .andExpect(jsonPath("$.terminalName").value("Device"))
                .andReturn().getResponse().getContentAsString();
        String session = IdentityFixtures.JSON.readTree(login).get("posSession").asString();

        mvc.perform(get(POS_SESSION).with(terminal.bearer()).with(posSession(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terminalCode").value(code))
                .andExpect(jsonPath("$.terminalName").value("Device"));
    }

    @Test
    void aPosSessionIgnoresATenantHeaderNamingAnotherTenant() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(terminal.bearer()).with(posSession(session))
                        .header("X-Tenant-Id", TenantTestSupport.A.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TenantTestSupport.BOOTSTRAP.toString()));
    }

    @Test
    void aPinLoginNeedsTheTerminalsToken() throws Exception {
        mvc.perform(loginRequest())
                .andExpect(status().isUnauthorized());
    }

    /**
     * FE-F14: the POS repeats a 401 carrying {@code WWW-Authenticate} with a new terminal token, so a wrong PIN must
     * not carry one (the PIN is never sent twice), while a missing or refused terminal token does.
     */
    @Test
    void aWrongPinIsAPlain401AndOnlyATerminalTokenRefusalIsChallenged() throws Exception {
        mvc.perform(post("/api/v1/auth/pos-login").with(terminal.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(IdentityFixtures.json("employeeCode", waiter.code(), "pin", "0000")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"));

        mvc.perform(loginRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(loginRequest().with(TestJwtFactory.account("kc-" + UUID.randomUUID()).bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
    }

    @Test
    void aSessionWithoutTheTerminalsTokenIsRefused() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(posSession(session)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionUsedWithAnotherTerminalsTokenIsRefused() throws Exception {
        Device other = devices.enrolledTerminal(store);
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(other.bearer()).with(posSession(session)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("POS_SESSION_INVALID"));
    }

    @Test
    void theTerminalsTokenAloneOpensNoPosRoute() throws Exception {
        mvc.perform(get(POS_SESSION).with(terminal.bearer()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("POS_SESSION_INVALID"));
        mvc.perform(get("/api/v1/pos/catalog").with(terminal.bearer()).with(posSession("not-a-session")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("POS_SESSION_INVALID"));
    }

    @Test
    void aBackOfficeTokenIsNotATerminal() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(TestJwtFactory.account("kc-" + UUID.randomUUID()).bearer())
                        .with(posSession(session)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));
    }

    @Test
    void aTenantRealmTokenOfAPosClientIsNotATerminal() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(TestJwtFactory.account(FakeDeviceProvider.subjectOf(terminal.clientId()))
                        .claim("azp", terminal.clientId()).bearer()).with(posSession(session)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBridgeTokenIsNotATerminal() throws Exception {
        Device bridge = devices.enrolledBridge(store);
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(bridge.bearer()).with(posSession(session)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTerminalTokenNeverReachesTheBackOffice() throws Exception {
        mvc.perform(get("/api/v1/backoffice/stores").with(terminal.bearer()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/tenants").with(terminal.bearer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTerminalNeverSelectsATenant() throws Exception {
        String session = login(terminal);

        mvc.perform(get(POS_SESSION).with(terminal.bearer()).with(posSession(session))
                        .header("X-Tenant-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()));
    }

    /** The PIN login with the terminal's bearer; @return the POS session token. */
    private String login(Device device) throws Exception {
        return IdentityFixtures.JSON.readTree(mvc.perform(loginRequest().with(device.bearer()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("posSession").asString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest() {
        return post("/api/v1/auth/pos-login").contentType(MediaType.APPLICATION_JSON)
                .content(IdentityFixtures.json("employeeCode", waiter.code(), "pin", PIN));
    }

    private static RequestPostProcessor posSession(String token) {
        return request -> {
            request.addHeader("X-Pos-Session", token);
            return request;
        };
    }
}
