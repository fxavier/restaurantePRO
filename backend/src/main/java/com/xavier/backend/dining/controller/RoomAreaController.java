package com.xavier.backend.dining.controller;

import com.xavier.backend.dining.model.dto.RoomAreaCreateRequest;
import com.xavier.backend.dining.model.dto.RoomAreaSummary;
import com.xavier.backend.dining.model.dto.RoomAreaUpdateRequest;
import com.xavier.backend.dining.service.RoomAreaService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** No DELETE: areas are deactivated with {@code PUT active=false}, because R2 occupations and reports refer to them. */
@RestController
@RequestMapping(RoomAreaController.PATH)
class RoomAreaController {

    static final String PATH = "/api/v1/backoffice/areas";

    private final RoomAreaService areas;

    RoomAreaController(RoomAreaService areas) {
        this.areas = areas;
    }

    @GetMapping
    PageResponse<RoomAreaSummary> list(@RequestParam UUID storeId, @PageableDefault(sort = "code") Pageable pageable) {
        return areas.list(storeId, pageable);
    }

    @GetMapping("/{id}")
    RoomAreaSummary get(@PathVariable UUID id) {
        return areas.get(id);
    }

    @PostMapping
    ResponseEntity<RoomAreaSummary> create(@Valid @RequestBody RoomAreaCreateRequest request) {
        RoomAreaSummary created = areas.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    RoomAreaSummary update(@PathVariable UUID id, @Valid @RequestBody RoomAreaUpdateRequest request) {
        return areas.update(id, request);
    }
}
