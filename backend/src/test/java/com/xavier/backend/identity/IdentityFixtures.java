package com.xavier.backend.identity;

import com.xavier.backend.access.TestAuthentications;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.config.security.TestJwtFactory;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds test data through the public HTTP API, as an installation-wide administrator actor (no login), and
 * logs in for real where a test is about the session. Fresh codes per call: the database is shared.
 */
public final class IdentityFixtures {

    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final UUID WAITER = UUID.fromString("01900000-0000-7000-8000-000000000303");
    public static final UUID MANAGER = UUID.fromString("01900000-0000-7000-8000-000000000302");
    public static final UUID ADMINISTRATOR = UUID.fromString("01900000-0000-7000-8000-000000000301");
    /** What the seeded waiter role holds: R1 views plus the D62, D92 and D134 defaults (V3005, V3010, V3012). */
    public static final Set<Permission> WAITER_PERMISSIONS = Set.of(Permission.DINING_VIEW, Permission.CATALOG_VIEW,
            Permission.ORDER_VIEW, Permission.ORDER_EDIT, Permission.ORDER_VOID, Permission.ORDER_TRANSFER,
            Permission.BILL_SPLIT, Permission.ORDER_DISCOUNT, Permission.ORDER_OFFER, Permission.LOYALTY_VIEW,
            Permission.LOYALTY_REDEEM, Permission.RESERVATION_VIEW, Permission.RESERVATION_MANAGE);

    /** The waiter's authorities in {@code store}, as a POS session holds them. */
    public static String[] waiterAuthorities(UUID store) {
        return WAITER_PERMISSIONS.stream().map(p -> p.authority(store)).toArray(String[]::new);
    }

    private final MockMvc mvc;

    public IdentityFixtures(MockMvc mvc) {
        this.mvc = mvc;
    }

    public static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Every permission, installation-wide. */
    public static RequestPostProcessor admin() {
        return as(Arrays.stream(Permission.values()).map(Permission::authority).collect(Collectors.toSet()));
    }

    /** An authenticated session of account {@code userId} without permissions (as after a real login). */
    public static RequestPostProcessor asAccount(UUID userId) {
        Actor actor = Actor.account(userId, Set.of());
        return authentication(TestAuthentications.of(actor));
    }

    public static RequestPostProcessor as(Set<String> authorities) {
        Actor actor = Actor.account(UUID.fromString("00000000-0000-7000-8000-000000000001"), authorities);
        return authentication(TestAuthentications.of(actor));
    }

    public UUID store() throws Exception {
        return id(mvc.perform(post("/api/v1/backoffice/stores").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "%s", "name": "Store", "timezone": "Africa/Maputo"}""".formatted(unique("S"))))
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    public void deactivateStore(UUID store) throws Exception {
        JsonNode current = JSON.readTree(mvc.perform(get("/api/v1/backoffice/stores/" + store).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/backoffice/stores/" + store).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "%s", "timezone": "%s", "businessDayCutoff": "%s",
                                 "active": false, "version": %d}""".formatted(current.get("code").asString(),
                                current.get("name").asString(),
                                current.get("timezone").asString(), current.get("businessDayCutoff").asString(),
                                current.get("version").asLong())))
                .andExpect(status().isOk());
    }

    /** An enrolled terminal of {@code store}; returns its device token. */
    public Terminal enrolledTerminal(UUID store) throws Exception {
        UUID terminal = id(mvc.perform(post("/api/v1/backoffice/terminals").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "code": "%s", "name": "Bar"}""".formatted(store, unique("T"))))
                .andExpect(status().isCreated()).andReturn().getResponse());
        String code = JSON.readTree(mvc.perform(post("/api/v1/backoffice/terminals/" + terminal + "/enrollment-code")
                        .with(admin()).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("code").asString();
        String token = JSON.readTree(mvc.perform(post("/api/v1/auth/terminals/enroll").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\"}".formatted(code)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("deviceToken").asString();
        return new Terminal(terminal, store, token);
    }

    public void revoke(Terminal terminal) throws Exception {
        mvc.perform(post("/api/v1/backoffice/terminals/" + terminal.id() + "/revoke").with(admin()).with(csrf()))
                .andExpect(status().isOk());
    }

    /** A new account of the bootstrap tenant, linked to {@code employeeId} when not {@code null}. */
    public User user(Set<UUID> globalRoles, UUID employeeId) throws Exception {
        String username = unique("u");
        UUID id = id(mvc.perform(post("/api/v1/backoffice/users").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "employeeId", employeeId, "roleIds", globalRoles)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        return new User(id, username);
    }

    /** A new account holding {@code globalRoles}. */
    public User newUser(Set<UUID> globalRoles) throws Exception {
        return user(globalRoles, null);
    }

    /** A new employee assigned to {@code store} with {@code role}. */
    public Employee employee(String pin, UUID store, UUID role) throws Exception {
        String code = unique("E");
        UUID id = id(mvc.perform(post("/api/v1/backoffice/employees").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code": "%s", "name": "Ana", "pin": "%s", "storeId": "%s", "roleId": "%s"}"""
                                .formatted(code, pin, store, role)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        return new Employee(id, code);
    }

    /**
     * {@code user} logged in through Keycloak: the {@code ACCOUNT} membership of its Keycloak user in the bootstrap
     * tenant (as an invitation creates it), and a fresh access token with a second factor on each request.
     */
    public static RequestPostProcessor login(User user) {
        String subject = "kc-" + user.id();
        OwnerDatabase.shared().jdbc().sql("INSERT INTO tenancy.tenant_membership (id, tenant_id, issuer, subject, "
                        + "kind, principal_id, status, valid_from, created_at) VALUES (?, ?, ?, ?, 'ACCOUNT', ?, "
                        + "'ACTIVE', now() - interval '1 minute', now()) ON CONFLICT (issuer, subject, tenant_id) "
                        + "DO NOTHING")
                .param(UUID.randomUUID()).param(TenantTestSupport.BOOTSTRAP).param(TestJwtFactory.TENANT_ISSUER)
                .param(subject).param(user.id()).update();
        return request -> TestJwtFactory.account(subject).bearer().postProcessRequest(request);
    }

    /** A POS session of {@code employee} on {@code terminal} ({@code X-Pos-Session}). */
    public RequestPostProcessor posLogin(Terminal terminal, Employee employee, String pin) throws Exception {
        return posSession(mvc.perform(posLoginRequest(terminal.token(), employee.code(), pin))
                .andExpect(status().isOk()).andReturn().getResponse());
    }

    /** The POS session a PIN login answered. */
    public static RequestPostProcessor posSession(MockHttpServletResponse login) throws Exception {
        String token = JSON.readTree(login.getContentAsString()).get("posSession").asString();
        return request -> {
            request.addHeader("X-Pos-Session", token);
            return request;
        };
    }

    public static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder posLoginRequest(
            String terminalToken, String employeeCode, String pin) throws Exception {
        var request = post("/api/v1/auth/pos-login").contentType(MediaType.APPLICATION_JSON)
                .content(json("employeeCode", employeeCode, "pin", pin));
        return terminalToken == null ? request : request.header("X-Terminal-Token", terminalToken);
    }

    /** JSON object from key/value pairs; values may be null. */
    public static String json(Object... keysAndValues) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            body.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return JSON.writeValueAsString(body);
    }

    public static UUID id(MockHttpServletResponse response) throws Exception {
        return UUID.fromString(JSON.readTree(response.getContentAsString()).get("id").asString());
    }

    public record Terminal(UUID id, UUID storeId, String token) {
    }

    public record User(UUID id, String username) {
    }

    public record Employee(UUID id, String code) {
    }
}
