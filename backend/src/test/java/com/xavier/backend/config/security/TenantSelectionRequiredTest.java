package com.xavier.backend.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xavier.backend.tenancy.MembershipSelectionRequiredException;
import com.xavier.backend.tenancy.TenancyApi;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hand-off (d): only "several memberships and no selection" is 400 {@code TENANT_SELECTION_REQUIRED}; any other
 * failure of the resolution (a broken database, a bug) is not disguised as a client error.
 */
class TenantSelectionRequiredTest {

    final TenancyApi tenancy = mock(TenancyApi.class);
    final TenantResolutionFilter filter = new TenantResolutionFilter(
            TrustedRealms.of(TestJwtFactory.TENANT_ISSUER, TestJwtFactory.OPS_ISSUER, "", issuer -> TestJwtFactory.keys()),
            tenancy, null, null, new ProblemDetailResponder(jsonMapper(), new SecurityProperties(null,
                    "restaurantpro-api", Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(2))), new SimpleMeterRegistry());

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void severalMembershipsWithoutASelectionAre400() throws Exception {
        when(tenancy.resolve(anyString(), anyString(), any(), any()))
                .thenThrow(new MembershipSelectionRequiredException());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(backOfficeRequest(), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("TENANT_SELECTION_REQUIRED");
    }

    @Test
    void anotherIllegalStateIsNotASelectionError() {
        when(tenancy.resolve(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("the database is gone"));

        assertThatThrownBy(() -> filter.doFilter(backOfficeRequest(), new MockHttpServletResponse(),
                new MockFilterChain())).isInstanceOf(IllegalStateException.class)
                .hasMessage("the database is gone");
    }

    private static MockHttpServletRequest backOfficeRequest() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claim("iss", TestJwtFactory.TENANT_ISSUER)
                .subject("kc-accountant").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        return new MockHttpServletRequest("GET", "/api/v1/backoffice/stores");
    }

    private static JsonMapper jsonMapper() {
        JsonMapper[] mapper = new JsonMapper[1];
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> mapper[0] = context.getBean(JsonMapper.class));
        return mapper[0];
    }
}
