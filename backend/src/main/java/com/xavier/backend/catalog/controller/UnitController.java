package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.UnitCreateRequest;
import com.xavier.backend.catalog.model.dto.UnitSummary;
import com.xavier.backend.catalog.model.dto.UnitUpdateRequest;
import com.xavier.backend.catalog.service.UnitService;
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
@RequestMapping(UnitController.PATH)
class UnitController {

    static final String PATH = "/api/v1/backoffice/catalog/units";

    private final UnitService units;

    UnitController(UnitService units) {
        this.units = units;
    }

    @GetMapping
    PageResponse<UnitSummary> list(@PageableDefault(sort = "code") Pageable pageable) {
        return units.list(pageable);
    }

    @GetMapping("/{id}")
    UnitSummary get(@PathVariable UUID id) {
        return units.get(id);
    }

    @PostMapping
    ResponseEntity<UnitSummary> create(@Valid @RequestBody UnitCreateRequest request) {
        UnitSummary created = units.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    UnitSummary update(@PathVariable UUID id, @Valid @RequestBody UnitUpdateRequest request) {
        return units.update(id, request);
    }
}
