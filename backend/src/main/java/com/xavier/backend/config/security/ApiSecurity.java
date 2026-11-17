package com.xavier.backend.config.security;

import com.xavier.backend.access.ActorDirectory;
import com.xavier.backend.shared.tenancy.CurrentAuthentication;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.tenancy.TenancyApi;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * The pieces every API chain shares (D176): stateless (no session, no cookie, no CSRF on the API: the credential is
 * the {@code Authorization} header, which a browser never sends on its own), no form login, no basic, and the
 * bearer-token pipeline: validation ({@link BearerAuthentication}), then {@link TenantResolutionFilter}. Public for
 * the Prometheus chain ({@code config.MetricsScrapeSecurity}), which accepts a {@code SYSTEM_MONITOR} holder too.
 */
@Component
public class ApiSecurity {

    private static final DefaultBearerTokenResolver TOKENS = new DefaultBearerTokenResolver();

    private final BearerAuthentication bearer;
    private final TenantResolutionFilter resolution;
    private final ProblemDetailResponder problems;

    ApiSecurity(TrustedRealms realms, SecurityProperties properties, Clock clock, TenancyApi tenancy,
                TenantScopes tenants, ActorDirectory actors, ProblemDetailResponder problems, MeterRegistry meters) {
        this.bearer = new BearerAuthentication(realms, properties, clock);
        this.resolution = new TenantResolutionFilter(realms, tenancy, tenants, actors, problems, meters);
        this.problems = problems;
    }

    /** Stateless defaults with the 401/403 bodies; no credential mechanism yet. */
    public HttpSecurity stateless(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(problems.forbidden()));
    }

    /** Bearer tokens of the BackOffice family (BFF, support) resolved to a tenant-bound actor: the actuator chains. */
    public HttpSecurity backOfficeBearer(HttpSecurity http) throws Exception {
        return bearer(http, RouteFamily.BACKOFFICE);
    }

    /** Bearer tokens of the request path's family: the API chain. */
    HttpSecurity bearerByPath(HttpSecurity http) throws Exception {
        return bearer(http, null);
    }

    private HttpSecurity bearer(HttpSecurity http, RouteFamily fixed) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, e) -> answer(familyOf(request, fixed), e, response);
        http.oauth2ResourceServer(resource -> resource
                .bearerTokenResolver(ApiSecurity::unlessAuthenticated)
                .authenticationManagerResolver(fixed == null ? bearer.byPath()
                        : request -> bearer.forFamily(fixed))
                .authenticationEntryPoint(entryPoint)
                .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                    @Override
                    public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler((request, response, e) ->
                                answer(familyOf(request, fixed), e, response));
                        return filter;
                    }
                }));
        http.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));
        return http.addFilterAfter(resolution, BearerTokenAuthenticationFilter.class);
    }

    /**
     * The bearer token, unless an earlier filter of the chain already authenticated the request (the Prometheus
     * scrape token is also a bearer header, D152, and is no JWT).
     */
    private static String unlessAuthenticated(HttpServletRequest request) {
        return CurrentAuthentication.read().isPresent() ? null : TOKENS.resolve(request);
    }

    private static RouteFamily familyOf(HttpServletRequest request, RouteFamily fixed) {
        return fixed != null ? fixed : RouteFamily.of(request).orElse(RouteFamily.BACKOFFICE);
    }

    /** 503 when the family's IdP cannot verify anything, 401 {@code invalid_token} for a bad token, else 401. */
    private void answer(RouteFamily family, AuthenticationException e, HttpServletResponse response)
            throws IOException, ServletException {
        if (!bearer.available(family) || e instanceof AuthenticationServiceException) {
            problems.idpUnavailable(response);
        } else if (e instanceof OAuth2AuthenticationException) {
            problems.invalidToken(response);
        } else {
            problems.unauthorized().commence(null, response, e);
        }
    }
}
