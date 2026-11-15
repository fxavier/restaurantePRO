package com.xavier.backend.config.security;

import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.xavier.backend.access.ActorDirectory;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.tenancy.TenantScopes;
import jakarta.servlet.DispatcherType;
import java.net.MalformedURLException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * HTTP security (spec §10; D176, D177, D184). The backend is an OAuth2 resource server for Keycloak's two realms:
 * every API call carries {@code Authorization: Bearer <JWT>}, validated completely ({@link BearerAuthentication}),
 * then resolved to a tenant and an actor ({@link TenantResolutionFilter}). No server-side session, no cookie, no CSRF
 * on the API (the BFF keeps CSRF on its own cookie session, P69). Fine-grained authorization is not here: every use
 * case calls {@code AccessPolicy.require}.
 *
 * <ul>
 *   <li>Actuator (management port): {@code health} open, the rest needs {@link Permission#SYSTEM_MONITOR}.</li>
 *   <li>POS ({@link RouteFamily#POS}), <b>interim until S73</b>: the device-token PIN login and the POS session token
 *       ({@code X-Pos-Session}) in the bootstrap tenant ({@link InterimPosBootstrapTenant}); 401
 *       {@code POS_SESSION_INVALID} without a valid session.</li>
 *   <li>Everything else under the API: a bearer token of the path's family. Swagger UI and {@code /v3/api-docs} need
 *       one too, unless {@code restaurantpro.dev.open-api-docs=true} (local development only).</li>
 *   <li>{@code ASYNC} and {@code ERROR} dispatches are permitted: the original request was already authorized (the
 *       SSE change stream, D60, completes on an async dispatch).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    static final String[] API_DOCS_PATHS = {"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**"};
    static final String[] OPEN_POS_ROUTES = {"/api/v1/auth/pos-login", "/api/v1/auth/terminals/enroll"};

    /**
     * The production realms: each issuer's key set at {@code <issuer>/protocol/openid-connect/certs} (Keycloak),
     * fetched lazily, cached, refreshed on an unknown {@code kid} at most once per 30 s (Nimbus defaults, D176), so
     * the backend starts while Keycloak is down.
     */
    @Bean
    TrustedRealms trustedRealms(SecurityProperties properties) {
        return TrustedRealms.of(properties.issuers().tenant(), properties.issuers().ops(), issuer -> {
            try {
                return JWKSourceBuilder.create(URI.create(issuer + "/protocol/openid-connect/certs").toURL())
                        .retrying(false).build();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Not an issuer URL: " + issuer, e);
            }
        });
    }

    @Bean
    @Order(1)
    SecurityFilterChain actuatorChain(HttpSecurity http, ApiSecurity api) throws Exception {
        api.stateless(http.securityMatcher(EndpointRequest.toAnyEndpoint()));
        return api.backOfficeBearer(http)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().hasAuthority(Permission.SYSTEM_MONITOR.authority()))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain posChain(HttpSecurity http, ApiSecurity api, TenantScopes tenants, ActorDirectory actors,
                                    ProblemDetailResponder problems) throws Exception {
        RequestMatcher pos = RouteFamily::isPos;
        return api.stateless(http.securityMatcher(pos))
                .addFilterBefore(new InterimPosBootstrapTenant(tenants), AnonymousAuthenticationFilter.class)
                .addFilterAfter(new PosSessionFilter(actors), InterimPosBootstrapTenant.class)
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, e) ->
                        problems.refuse(response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                                "POS_SESSION_INVALID", "Log in on the terminal again.")))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(OPEN_POS_ROUTES).permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain apiChain(HttpSecurity http, ApiSecurity api,
                                    @Value("${restaurantpro.dev.open-api-docs:false}") boolean openApiDocs)
            throws Exception {
        api.stateless(http);
        return api.bearerByPath(http)
                .authorizeHttpRequests(requests -> {
                    requests.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll();
                    if (openApiDocs) {
                        requests.requestMatchers(API_DOCS_PATHS).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .build();
    }
}
