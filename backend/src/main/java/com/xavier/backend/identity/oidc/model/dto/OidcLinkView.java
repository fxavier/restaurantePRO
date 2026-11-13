package com.xavier.backend.identity.oidc.model.dto;

/** Where the browser goes to authenticate at the IdP; the callback links that identity to this session's account. */
public record OidcLinkView(String authorizeUri) {
}
