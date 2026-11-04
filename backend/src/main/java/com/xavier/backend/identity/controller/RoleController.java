package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.RoleCreateRequest;
import com.xavier.backend.identity.model.dto.RoleSummary;
import com.xavier.backend.identity.model.dto.RoleUpdateRequest;
import com.xavier.backend.identity.service.RoleService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backoffice/roles")
class RoleController {

    private final RoleService roles;

    RoleController(RoleService roles) {
        this.roles = roles;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RoleSummary create(@Valid @RequestBody RoleCreateRequest request) {
        return roles.create(request);
    }

    @GetMapping
    PageResponse<RoleSummary> list(Pageable pageable) {
        return roles.list(pageable);
    }

    @GetMapping("/{id}")
    RoleSummary get(@PathVariable UUID id) {
        return roles.get(id);
    }

    @PutMapping("/{id}")
    RoleSummary update(@PathVariable UUID id, @Valid @RequestBody RoleUpdateRequest request) {
        return roles.update(id, request);
    }
}
