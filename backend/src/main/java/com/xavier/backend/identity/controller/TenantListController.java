package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.TenantChoice;
import com.xavier.backend.tenancy.TenancyApi;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own active memberships (D177 amended), for the tenant choice of a subject with several: it needs a
 * valid token and no {@code X-Tenant-Id}, and lists only the memberships of the token's {@code (iss, sub)}, so it
 * never reveals another tenant.
 */
@RestController
class TenantListController {

    private final TenancyApi tenancy;

    TenantListController(TenancyApi tenancy) {
        this.tenancy = tenancy;
    }

    @GetMapping("/api/v1/auth/tenants")
    List<TenantChoice> tenants(@AuthenticationPrincipal Jwt jwt) {
        return tenancy.membershipSummaries(jwt.getClaimAsString("iss"), jwt.getSubject()).stream()
                .map(TenantChoice::from).toList();
    }
}
