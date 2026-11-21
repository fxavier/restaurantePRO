package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.PermissionView;
import com.xavier.backend.identity.service.RoleService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The permission catalogue the BackOffice role editor offers (read-only: permissions are code-defined, D27). */
@RestController
class PermissionController {

    private final RoleService roles;

    PermissionController(RoleService roles) {
        this.roles = roles;
    }

    @GetMapping("/api/v1/backoffice/permissions")
    List<PermissionView> list() {
        return roles.permissions();
    }
}
