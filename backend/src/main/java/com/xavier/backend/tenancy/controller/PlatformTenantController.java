package com.xavier.backend.tenancy.controller;

import com.xavier.backend.tenancy.model.dto.OnboardTenantRequest;
import com.xavier.backend.tenancy.model.dto.PlatformTenantView;
import com.xavier.backend.tenancy.model.dto.TenantStatusChangeRequest;
import com.xavier.backend.tenancy.service.PlatformTenantService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform tenant lifecycle (D181). {@code /api/v1/platform/**} admits only an ops-realm token holding
 * {@code platform-operator} (S72's {@code SecurityConfig}; 403 {@code PLATFORM_FORBIDDEN} otherwise). The operator
 * recorded in the platform audit is the token's subject. Onboarding answers 202: the event chain completes it.
 */
@RestController
@RequestMapping("/api/v1/platform/tenants")
class PlatformTenantController {

    private final PlatformTenantService platform;

    PlatformTenantController(PlatformTenantService platform) {
        this.platform = platform;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    PlatformTenantView onboard(@Valid @RequestBody OnboardTenantRequest request, Authentication operator) {
        return platform.onboard(request, operator.getName());
    }

    @PostMapping("/{id}/suspend")
    PlatformTenantView suspend(@PathVariable UUID id, @Valid @RequestBody TenantStatusChangeRequest request,
                               Authentication operator) {
        return platform.suspend(id, request.reason(), operator.getName());
    }

    @PostMapping("/{id}/reactivate")
    PlatformTenantView reactivate(@PathVariable UUID id, @Valid @RequestBody TenantStatusChangeRequest request,
                                  Authentication operator) {
        return platform.reactivate(id, request.reason(), operator.getName());
    }

    @PostMapping("/{id}/close")
    PlatformTenantView close(@PathVariable UUID id, @Valid @RequestBody TenantStatusChangeRequest request,
                             Authentication operator) {
        return platform.close(id, request.reason(), operator.getName());
    }
}
