package com.xavier.backend.identity.oidc.controller;

import com.xavier.backend.identity.oidc.ConditionalOnOidcEnabled;
import com.xavier.backend.identity.oidc.ExternalIdentityService;
import com.xavier.backend.identity.oidc.OidcRoutes;
import com.xavier.backend.identity.oidc.exception.OidcUnavailableException;
import com.xavier.backend.identity.oidc.model.dto.ExternalIdentityView;
import com.xavier.backend.identity.oidc.model.dto.OidcLinkView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own federation (D144), under the open {@code /api/v1/auth/**}: every use case checks the session
 * itself. CSRF applies to {@code POST} and {@code DELETE}. No Idempotency-Key: linking only marks the session.
 */
@RestController
@ConditionalOnOidcEnabled
class OidcController {

    private final ExternalIdentityService identities;

    OidcController(ExternalIdentityService identities) {
        this.identities = identities;
    }

    /**
     * Reached only when Spring's redirect filter resolved no authorization request for {@code /authorize}: the
     * provider could not be discovered.
     */
    @GetMapping(OidcRoutes.AUTHORIZE)
    void authorize() {
        throw new OidcUnavailableException();
    }

    @PostMapping(OidcRoutes.LINK)
    OidcLinkView startLink(HttpServletRequest request) {
        identities.startLink(request.getSession());
        return new OidcLinkView(OidcRoutes.AUTHORIZE);
    }

    @GetMapping(OidcRoutes.LINK)
    ExternalIdentityView ownLink(HttpServletRequest request) {
        return identities.ownLink(request.getSession(false));
    }

    @DeleteMapping(OidcRoutes.LINK)
    ResponseEntity<Void> unlink(HttpServletRequest request) {
        identities.unlinkOwn(request.getSession(false));
        return ResponseEntity.noContent().build();
    }
}
