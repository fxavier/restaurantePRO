package com.xavier.backend.config;

import com.xavier.backend.identity.Permission;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP security (spec §10, D25-D27).
 *
 * <ul>
 *   <li>{@code /api/v1/auth/**} is open (login flows, S2); everything else needs an authenticated session.
 *       Fine-grained authorization is not here: every use case calls {@code AccessPolicy.require}.</li>
 *   <li>Server-side sessions in PostgreSQL (Spring Session JDBC); cookie Secure/HttpOnly/SameSite=Lax
 *       via {@code server.servlet.session.cookie.*}.</li>
 *   <li>CSRF on: SPA pattern, readable {@code XSRF-TOKEN} cookie echoed in the {@code X-XSRF-TOKEN} header.</li>
 *   <li>No form login, no HTTP basic: S2 adds JSON login endpoints under {@code /api/v1/auth}.</li>
 *   <li>401/403 are ProblemDetails.</li>
 *   <li>Actuator (management port): {@code health} open, the rest needs {@link Permission#SYSTEM_MONITOR}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String AUTH_PATHS = "/api/v1/auth/**";

    @Bean
    @Order(1)
    SecurityFilterChain actuatorSecurity(HttpSecurity http, ProblemDetailResponder problems) throws Exception {
        return commonDefaults(http.securityMatcher(EndpointRequest.toAnyEndpoint()), problems)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .anyRequest().hasAuthority(Permission.SYSTEM_MONITOR.authority()))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, ProblemDetailResponder problems) throws Exception {
        return commonDefaults(http, problems)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(AUTH_PATHS).permitAll()
                        .anyRequest().authenticated())
                .build();
    }

    private static HttpSecurity commonDefaults(HttpSecurity http, ProblemDetailResponder problems) throws Exception {
        return http
                .csrf(csrf -> csrf.spa())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems.unauthorized())
                        .accessDeniedHandler(problems.forbidden()));
    }
}
