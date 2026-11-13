package com.xavier.backend.identity.oidc.controller;

import com.xavier.backend.identity.oidc.OidcProperties;
import com.xavier.backend.identity.oidc.OidcRoutes;
import com.xavier.backend.identity.oidc.model.dto.OidcStatusView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Whether the login page offers federated login (D144). Answers when OIDC is disabled too ({@code enabled: false}),
 * so the page needs no special case; it calls nobody and reveals nothing but the configured label.
 */
@RestController
class OidcStatusController {

    private final OidcProperties properties;

    OidcStatusController(OidcProperties properties) {
        this.properties = properties;
    }

    @GetMapping(OidcRoutes.BASE)
    OidcStatusView status() {
        return new OidcStatusView(properties.enabled(), properties.enabled() ? properties.displayName() : null);
    }
}
