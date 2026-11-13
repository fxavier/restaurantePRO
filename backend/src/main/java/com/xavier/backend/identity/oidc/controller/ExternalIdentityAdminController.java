package com.xavier.backend.identity.oidc.controller;

import com.xavier.backend.identity.oidc.ConditionalOnOidcEnabled;
import com.xavier.backend.identity.oidc.ExternalIdentityService;
import com.xavier.backend.identity.oidc.model.dto.ExternalIdentityView;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An administrator's view and removal of an account's identity-provider link ({@code USER_MANAGE}, D144). */
@RestController
@ConditionalOnOidcEnabled
@RequestMapping("/api/v1/backoffice/users/{id}/external-identity")
class ExternalIdentityAdminController {

    private final ExternalIdentityService identities;

    ExternalIdentityAdminController(ExternalIdentityService identities) {
        this.identities = identities;
    }

    @GetMapping
    ExternalIdentityView link(@PathVariable UUID id) {
        return identities.linkOf(id);
    }

    @DeleteMapping
    ResponseEntity<Void> unlink(@PathVariable UUID id) {
        identities.unlinkAccount(id);
        return ResponseEntity.noContent().build();
    }
}
