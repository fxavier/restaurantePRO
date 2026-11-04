package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.FamilyCreateRequest;
import com.xavier.backend.catalog.model.dto.FamilySummary;
import com.xavier.backend.catalog.model.dto.FamilyUpdateRequest;
import com.xavier.backend.catalog.service.FamilyService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(FamilyController.PATH)
class FamilyController {

    static final String PATH = "/api/v1/backoffice/catalog/families";

    private final FamilyService families;

    FamilyController(FamilyService families) {
        this.families = families;
    }

    @GetMapping
    PageResponse<FamilySummary> list(@PageableDefault(sort = "code") Pageable pageable) {
        return families.list(pageable);
    }

    @GetMapping("/{id}")
    FamilySummary get(@PathVariable UUID id) {
        return families.get(id);
    }

    @PostMapping
    ResponseEntity<FamilySummary> create(@Valid @RequestBody FamilyCreateRequest request) {
        FamilySummary created = families.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    FamilySummary update(@PathVariable UUID id, @Valid @RequestBody FamilyUpdateRequest request) {
        return families.update(id, request);
    }
}
