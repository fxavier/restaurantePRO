package com.xavier.archfixture.forbidden.orders;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Test fixture only: a module other than identity or config using OAuth2, forbidden by D137. */
public class OidcShortcut {

    public String waiterOf(OidcUser user) {
        return user.getSubject();
    }
}
