package com.xavier.backend.identity.oidc;

import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * {@code GET /api/v1/auth/oidc/authorize} starts the flow for the one registration (Spring's default route would be
 * {@code …/authorize/{registrationId}}). While the provider cannot be discovered it resolves nothing, so the request
 * reaches {@code OidcController.authorize}, which answers 503 {@code OIDC_UNAVAILABLE}: Spring's redirect filter would
 * turn the exception into a bare 500.
 */
class FixedRouteAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    FixedRouteAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, OidcRoutes.AUTHORIZE);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        boolean authorize = HttpMethod.GET.matches(request.getMethod())
                && OidcRoutes.AUTHORIZE.equals(request.getRequestURI().substring(request.getContextPath().length()));
        return authorize ? resolve(request, LazyClientRegistrationRepository.REGISTRATION_ID) : null;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        try {
            return delegate.resolve(request, clientRegistrationId);
        } catch (OidcUnavailableException e) {
            return null;
        }
    }
}
