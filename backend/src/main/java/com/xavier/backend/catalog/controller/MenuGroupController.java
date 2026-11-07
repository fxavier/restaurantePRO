package com.xavier.backend.catalog.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.catalog.model.dto.MenuGroupCreateRequest;
import com.xavier.backend.catalog.model.dto.MenuGroupSummary;
import com.xavier.backend.catalog.model.dto.MenuGroupUpdateRequest;
import com.xavier.backend.catalog.service.MenuGroupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(MenuGroupController.PATH)
class MenuGroupController {

    static final String PATH = ItemController.PATH + "/{itemId}/menu-groups";

    private final MenuGroupService groups;
    private final IdempotentRequests idempotency;

    MenuGroupController(MenuGroupService groups, IdempotentRequests idempotency) {
        this.groups = groups;
        this.idempotency = idempotency;
    }

    @GetMapping
    List<MenuGroupSummary> list(@PathVariable UUID itemId) {
        return groups.list(itemId);
    }

    @PostMapping
    ResponseEntity<MenuGroupSummary> create(@PathVariable UUID itemId, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody MenuGroupCreateRequest body,
                                            HttpServletRequest request) {
        return idempotency.execute("catalog.menu-group.create", key, request, body, MenuGroupSummary.class, () -> {
            MenuGroupSummary created = groups.create(itemId, body);
            return ResponseEntity.created(URI.create(ItemController.PATH + "/" + itemId + "/menu-groups/"
                    + created.id())).body(created);
        });
    }

    @PutMapping("/{groupId}")
    MenuGroupSummary update(@PathVariable UUID itemId, @PathVariable UUID groupId,
                            @Valid @RequestBody MenuGroupUpdateRequest body) {
        return groups.update(itemId, groupId, body);
    }

    @DeleteMapping("/{groupId}")
    ResponseEntity<Void> delete(@PathVariable UUID itemId, @PathVariable UUID groupId, @RequestParam long version) {
        groups.delete(itemId, groupId, version);
        return ResponseEntity.noContent().build();
    }
}
