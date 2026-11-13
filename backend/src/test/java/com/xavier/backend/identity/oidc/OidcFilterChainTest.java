package com.xavier.backend.identity.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * D144 wiring: when enabled, the OIDC filters sit in the API chain only. Spring Security 7 would apply a
 * {@code Customizer} bean to every chain, the actuator's too; this pins that it does not happen.
 */
@OidcTest
class OidcFilterChainTest {

    @Autowired
    FilterChainProxy filterChainProxy;

    @Test
    void theOidcFiltersAreInTheApiChainOnly() {
        List<SecurityFilterChain> chains = filterChainProxy.getFilterChains();
        SecurityFilterChain api = chains.stream().filter(chain -> chain instanceof DefaultSecurityFilterChain d
                && d.getRequestMatcher() instanceof AnyRequestMatcher).findFirst().orElseThrow();

        assertThat(chains).hasSizeGreaterThan(1);
        assertThat(oauth2Filters(api)).hasSize(2);
        assertThat(chains.stream().filter(chain -> chain != api).flatMap(chain -> oauth2Filters(chain).stream()))
                .isEmpty();
    }

    private static List<Object> oauth2Filters(SecurityFilterChain chain) {
        return chain.getFilters().stream().filter(filter -> filter instanceof OAuth2LoginAuthenticationFilter
                || filter instanceof OAuth2AuthorizationRequestRedirectFilter).map(Object.class::cast).toList();
    }
}
