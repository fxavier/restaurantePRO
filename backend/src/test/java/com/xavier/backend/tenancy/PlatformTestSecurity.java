package com.xavier.backend.tenancy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.tenancy.TenantScopes;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The {@code /platform/**} rule of the spec (D181), test-only: S72's {@code SecurityConfig} owns the production one.
 * Only a token of the ops issuer holding the realm role {@code platform-operator} passes; anything else is 403
 * {@code PLATFORM_FORBIDDEN}, a missing token 401. Tokens are built by {@link #operator()}, {@link #tenantUser()} and
 * {@link #opsWithoutRole()} ({@code jwt()} post-processor: the decoder never runs, so it refuses every raw token).
 * Deleted once S72 is merged: PlatformAuthorizationTest then runs against the real chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PlatformTestSecurity {

    public static final String OPS_ISSUER = "https://auth.test/realms/restaurantpro-ops";
    public static final String TENANT_ISSUER = "https://auth.test/realms/restaurantpro";
    public static final String OPERATOR = "operator-" + UUID.randomUUID();

    static final String FORBIDDEN_BODY = "{\"status\":403,\"title\":\"Forbidden\",\"code\":\"PLATFORM_FORBIDDEN\","
            + "\"detail\":\"Only a platform operator may do this.\"}";

    @Bean
    @Order(0)
    SecurityFilterChain platformTestSecurity(HttpSecurity http, TenantScopes tenants) throws Exception {
        return http.securityMatcher("/api/v1/platform/**")
                .addFilterAfter(unbound(tenants), AnonymousAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(token -> {
                    throw new BadJwtException("test decoder: tokens come from jwt()");
                })))
                .authorizeHttpRequests(requests -> requests.anyRequest()
                        .access((authentication, context) -> new AuthorizationDecision(
                                isOperator(authentication.get()))))
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) -> {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.getWriter().write(FORBIDDEN_BODY);
                }))
                .build();
    }

    /**
     * S72's resolution binds no tenant to an ops-realm request (an operator has no membership); until S72, S71's
     * interim filter binds every request to the bootstrap tenant, which the platform functions refuse (TN004). This
     * stands in for S72 on the platform routes only.
     */
    private static Filter unbound(TenantScopes tenants) {
        return (request, response, chain) -> {
            Exception[] failure = new Exception[1];
            tenants.callUnbound(SystemScope.HOUSEKEEPING, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    failure[0] = e;
                }
                return null;
            });
            if (failure[0] instanceof IOException io) {
                throw io;
            }
            if (failure[0] instanceof ServletException servlet) {
                throw servlet;
            }
        };
    }

    private static boolean isOperator(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Map<String, Object> realm = token.getToken().getClaimAsMap("realm_access");
        return OPS_ISSUER.equals(token.getToken().getClaimAsString("iss")) && realm != null
                && realm.get("roles") instanceof Collection<?> roles && roles.contains("platform-operator");
    }

    public static RequestPostProcessor operator() {
        return token(OPS_ISSUER, OPERATOR, List.of("platform-operator"));
    }

    public static RequestPostProcessor opsWithoutRole() {
        return token(OPS_ISSUER, "support-" + UUID.randomUUID(), List.of("support"));
    }

    /** A tenant-realm user who even holds a role named {@code platform-operator}: the issuer decides. */
    public static RequestPostProcessor tenantUser() {
        return token(TENANT_ISSUER, "user-" + UUID.randomUUID(), List.of("platform-operator"));
    }

    private static RequestPostProcessor token(String issuer, String subject, List<String> roles) {
        return jwt().jwt(builder -> builder.claim("iss", issuer).subject(subject)
                .claim("realm_access", Map.of("roles", roles)));
    }
}
