package com.xavier.backend.identity;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    public static final String STRONG_PASSWORD = "correct horse battery";
    public static final UUID WAITER = UUID.fromString("01900000-0000-7000-8000-000000000303");
    public static final UUID MANAGER = UUID.fromString("01900000-0000-7000-8000-000000000302");
    public static final UUID ADMINISTRATOR = UUID.fromString("01900000-0000-7000-8000-000000000301");

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

    public static RequestPostProcessor as(Set<String> authorities) {
        Actor actor = new Actor(UUID.fromString("00000000-0000-7000-8000-000000000001"), null, null, null,
                authorities);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                authorities.stream().map(SimpleGrantedAuthority::new).toList()));
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

    /** A new account whose password is already changed, so it holds its permissions. */
    public User user(Set<UUID> globalRoles, UUID employeeId) throws Exception {
        String username = unique("u");
        UUID id = id(mvc.perform(post("/api/v1/backoffice/users").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "initialPassword", "initial password 1",
                                "employeeId", employeeId, "roleIds", globalRoles)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        Cookie session = login(username, "initial password 1");
        mvc.perform(post("/api/v1/auth/password").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword": "initial password 1", "newPassword": "%s"}"""
                                .formatted(STRONG_PASSWORD)))
                .andExpect(status().isOk());
        return new User(id, username);
    }

    /** A new account that still has its initial password. */
    public User newUser(String initialPassword, Set<UUID> globalRoles) throws Exception {
        String username = unique("u");
        UUID id = id(mvc.perform(post("/api/v1/backoffice/users").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "initialPassword", initialPassword,
                                "roleIds", globalRoles)))
                .andExpect(status().isCreated()).andReturn().getResponse());
        return new User(id, username);
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

    public Cookie login(String username, String password) throws Exception {
        return mvc.perform(loginRequest(username, password))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    public static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(
            String username, String password) throws Exception {
        return post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json("username", username, "password", password));
    }

    public Cookie posLogin(Terminal terminal, Employee employee, String pin) throws Exception {
        return mvc.perform(posLoginRequest(terminal.token(), employee.code(), pin))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("SESSION");
    }

    public static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder posLoginRequest(
            String terminalToken, String employeeCode, String pin) throws Exception {
        var request = post("/api/v1/auth/pos-login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
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
