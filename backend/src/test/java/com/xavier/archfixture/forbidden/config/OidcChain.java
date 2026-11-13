package com.xavier.archfixture.forbidden.config;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Test fixture only: config wires the filter chain (D137), so its OAuth2 use is allowed. */
public class OidcChain {

    public boolean signedIn(OidcUser user) {
        return user != null;
    }
}
