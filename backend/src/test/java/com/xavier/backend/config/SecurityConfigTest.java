package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.identity.AccessPolicy;
import com.xavier.backend.identity.Permission;
import com.xavier.backend.identity.WithActor;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SecurityConfigTest.ProbeController.class})
class SecurityConfigTest {

    static final String STORE_A = "0190a000-0000-7000-8000-00000000000a";
    static final String STORE_B = "0190b000-0000-7000-8000-00000000000b";

    @Autowired
    MockMvc mvc;

    @Test
    void anUnauthenticatedRequestGets401ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/backoffice/security-probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void thereIsNoDefaultUserWithAGeneratedPassword(@Autowired ApplicationContext context) {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void authEndpointsAreReachableWithoutASession() throws Exception {
        mvc.perform(get("/api/v1/auth/security-probe"))
                .andExpect(status().isOk());
    }

    @Test
    @WithActor
    void aMutatingRequestWithoutTheCsrfTokenGets403ProblemDetail() throws Exception {
        mvc.perform(post("/api/v1/backoffice/security-probe"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithActor
    void theSpaEchoesTheCsrfCookieAsAHeader() throws Exception {
        Cookie csrfCookie = mvc.perform(get("/api/v1/backoffice/security-probe"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).as("XSRF-TOKEN cookie readable by the SPA").isNotNull();
        assertThat(csrfCookie.isHttpOnly()).isFalse();

        mvc.perform(post("/api/v1/backoffice/security-probe")
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isOk())
                .andExpect(content().string("written"));
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = STORE_A)
    void aStoreScopedUseCaseAllowsOnlyTheActorsStore() throws Exception {
        Cookie csrf = csrfCookie();

        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_A)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_B)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A)
    void anActorWithoutThePermissionGets403() throws Exception {
        Cookie csrf = csrfCookie();

        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_A)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    private Cookie csrfCookie() throws Exception {
        return mvc.perform(get("/api/v1/backoffice/security-probe")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    /** Stands in for real controllers: a use case that authorizes through AccessPolicy. */
    @RestController
    static class ProbeController {

        private final AccessPolicy accessPolicy;

        ProbeController(AccessPolicy accessPolicy) {
            this.accessPolicy = accessPolicy;
        }

        @GetMapping("/api/v1/backoffice/security-probe")
        String read() {
            return "ok";
        }

        @PostMapping("/api/v1/backoffice/security-probe")
        String write() {
            return "written";
        }

        @PostMapping("/api/v1/backoffice/stores/{storeId}/security-probe")
        String storeScopedWrite(@PathVariable UUID storeId) {
            accessPolicy.require(Permission.DINING_MANAGE, storeId);
            return "store write";
        }

        @GetMapping("/api/v1/auth/security-probe")
        String publicAuthEndpoint() {
            return "public";
        }

    }
}
