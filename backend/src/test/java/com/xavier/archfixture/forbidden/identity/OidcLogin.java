package com.xavier.archfixture.forbidden.identity;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/** Test fixture only: identity owns federation (D144), so its OAuth2 use is allowed. */
public class OidcLogin {

    public String subjectOf(OidcUser user) {
        return user.getSubject();
    }
}
