package com.xavier.backend.dining.controller;

import com.xavier.backend.dining.model.dto.RestaurantTableCreateRequest;
import com.xavier.backend.dining.model.dto.RestaurantTableSummary;
import com.xavier.backend.dining.model.dto.RestaurantTableUpdateRequest;
import com.xavier.backend.dining.service.RestaurantTableService;
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

/** No DELETE: tables are deactivated with {@code PUT active=false}, because R2 occupations and orders refer to them. */
@RestController
@RequestMapping(RestaurantTableController.PATH)
class RestaurantTableController {

    static final String PATH = "/api/v1/backoffice/tables";

    private final RestaurantTableService tables;

    RestaurantTableController(RestaurantTableService tables) {
        this.tables = tables;
    }

    @GetMapping
    PageResponse<RestaurantTableSummary> list(@RequestParam UUID storeId,
                                              @PageableDefault(sort = "code") Pageable pageable) {
        return tables.list(storeId, pageable);
    }

    @GetMapping("/{id}")
    RestaurantTableSummary get(@PathVariable UUID id) {
        return tables.get(id);
    }

    @PostMapping
    ResponseEntity<RestaurantTableSummary> create(@Valid @RequestBody RestaurantTableCreateRequest request) {
        RestaurantTableSummary created = tables.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    RestaurantTableSummary update(@PathVariable UUID id, @Valid @RequestBody RestaurantTableUpdateRequest request) {
        return tables.update(id, request);
    }
}
