package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.UserCreateRequest;
import com.xavier.backend.identity.model.dto.UserSummary;
import com.xavier.backend.identity.model.dto.UserUpdateRequest;
import com.xavier.backend.identity.service.UserAccountService;
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
@RequestMapping("/api/v1/backoffice/users")
class UserController {

    private final UserAccountService users;

    UserController(UserAccountService users) {
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UserSummary create(@Valid @RequestBody UserCreateRequest request) {
        return users.create(request);
    }

    @GetMapping
    PageResponse<UserSummary> list(Pageable pageable) {
        return users.list(pageable);
    }

    @GetMapping("/{id}")
    UserSummary get(@PathVariable UUID id) {
        return users.get(id);
    }

    @PutMapping("/{id}")
    UserSummary update(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequest request) {
        return users.update(id, request);
    }
}
