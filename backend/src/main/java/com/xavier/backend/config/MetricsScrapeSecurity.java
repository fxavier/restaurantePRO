package com.xavier.backend.config;

import com.xavier.backend.access.Permission;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * D152 (amends D33): the Prometheus endpoint ({@code /actuator/prometheus}, management port) answers either a
 * {@code SYSTEM_MONITOR} session, as every other Actuator endpoint, or {@code Authorization: Bearer <token>} when
 * {@code restaurantpro.metrics.token-file} names the token's file (a Compose secret). The token is compared as SHA-256
 * digests in constant time, is never stored in a session, and grants nothing else: this chain matches only that
 * endpoint, so the header means nothing anywhere else. Anything else gets 401 (403 for a session without the
 * permission). A configured file that is missing or empty stops the start (fail fast).
 */
@Configuration(proxyBeanMethods = false)
class MetricsScrapeSecurity {

    static final String SCRAPER = "metrics-scraper";
    static final String SCRAPE_AUTHORITY = "METRICS_SCRAPE";
    private static final String BEARER = "Bearer ";

    @Bean
    @Order(0)
    SecurityFilterChain prometheusScrapeSecurity(HttpSecurity http, ProblemDetailResponder problems,
                                                  @Value("${restaurantpro.metrics.token-file:}") String tokenFile)
            throws Exception {
        Optional<byte[]> digest = tokenDigest(tokenFile);
        if (digest.isPresent()) {
            http.addFilterBefore(new ScrapeTokenFilter(digest.get()), AnonymousAuthenticationFilter.class);
        }
        return http.securityMatcher(EndpointRequest.to("prometheus"))
                .csrf(csrf -> csrf.spa())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(problems.unauthorized())
                        .accessDeniedHandler(problems.forbidden()))
                .authorizeHttpRequests(requests -> requests.anyRequest()
                        .hasAnyAuthority(Permission.SYSTEM_MONITOR.authority(), SCRAPE_AUTHORITY))
                .build();
    }

    /**
     * The SHA-256 of the token in {@code tokenFile} (surrounding whitespace removed); empty when no file is set.
     *
     * @throws IllegalStateException when the file is set but unreadable or holds no token
     */
    static Optional<byte[]> tokenDigest(String tokenFile) {
        if (tokenFile.isBlank()) {
            return Optional.empty();
        }
        String token;
        try {
            token = Files.readString(Path.of(tokenFile)).strip();
        } catch (IOException e) {
            throw new IllegalStateException("restaurantpro.metrics.token-file cannot be read: " + tokenFile, e);
        }
        if (token.isEmpty()) {
            throw new IllegalStateException("restaurantpro.metrics.token-file is empty: " + tokenFile);
        }
        return Optional.of(sha256(token));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** Authenticates a request carrying the scrape token, for this request only. */
    private static final class ScrapeTokenFilter extends OncePerRequestFilter {

        private final byte[] digest;

        private ScrapeTokenFilter(byte[] digest) {
            this.digest = digest.clone();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith(BEARER)
                    && MessageDigest.isEqual(digest, sha256(authorization.substring(BEARER.length())))) {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(SCRAPER, null,
                        List.of(new SimpleGrantedAuthority(SCRAPE_AUTHORITY))));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(request, response);
        }
    }
}
