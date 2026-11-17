package com.xavier.bff;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.util.WebUtils;

/**
 * Serialises token authorization per BFF session. Keycloak rotates the refresh token and revokes the session
 * when a used one comes back (D175), so parallel calls on one session must not each refresh: the first refreshes,
 * the others wait and then read the rotated client the first one stored in the session.
 * <p>
 * ponytail: the lock is the session mutex of this JVM, so it holds for one BFF instance or sticky sessions only;
 * scaling the BFF out (D188) needs a shared session store and a lock that spans instances.
 */
final class OneRefreshPerSession implements OAuth2AuthorizedClientManager {

    private final OAuth2AuthorizedClientManager delegate;

    OneRefreshPerSession(OAuth2AuthorizedClientManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizedClient authorize(OAuth2AuthorizeRequest authorizeRequest) {
        HttpServletRequest request = authorizeRequest.getAttribute(HttpServletRequest.class.getName());
        synchronized (WebUtils.getSessionMutex(request.getSession())) {
            return delegate.authorize(authorizeRequest);
        }
    }
}
