package com.xavier.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.WithActor;
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

    /** D176: no session at all, so no {@code /auth/**} route is open any more (BackOffice login is Keycloak's). */
    @Test
    void authEndpointsNeedATokenToo() throws Exception {
        mvc.perform(get("/api/v1/auth/security-probe")).andExpect(status().isUnauthorized());
    }

    /** D176: the credential is the Authorization header, so the API needs no CSRF token, sets no cookie. */
    @Test
    @WithActor
    void aMutatingRequestNeedsNoCsrfTokenAndNoCookieIsSet() throws Exception {
        mvc.perform(post("/api/v1/backoffice/security-probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("written"))
                .andExpect(result -> assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty());
    }

    @Test
    @WithActor(permissions = Permission.DINING_MANAGE, storeId = STORE_A)
    void aStoreScopedUseCaseAllowsOnlyTheActorsStore() throws Exception {
        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_A))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_B))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @WithActor(permissions = Permission.DINING_VIEW, storeId = STORE_A)
    void anActorWithoutThePermissionGets403() throws Exception {
        mvc.perform(post("/api/v1/backoffice/stores/{id}/security-probe", STORE_A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
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
