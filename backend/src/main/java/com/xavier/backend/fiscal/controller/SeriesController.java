package com.xavier.backend.fiscal.controller;

import com.xavier.backend.fiscal.model.dto.SeriesCreateRequest;
import com.xavier.backend.fiscal.model.dto.SeriesView;
import com.xavier.backend.fiscal.service.SeriesService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice series. No PUT/PATCH: nothing may change a series' next number (spec §9). */
@RestController
@RequestMapping(SeriesController.PATH)
class SeriesController {

    static final String PATH = "/api/v1/backoffice/fiscal/series";

    private final SeriesService series;

    SeriesController(SeriesService series) {
        this.series = series;
    }

    @GetMapping
    PageResponse<SeriesView> list(@RequestParam(required = false) UUID storeId,
                                  @PageableDefault(sort = "code") Pageable pageable) {
        return series.list(storeId, pageable);
    }

    @PostMapping
    ResponseEntity<SeriesView> create(@Valid @RequestBody SeriesCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(series.create(request));
    }

    @PostMapping("/{id}/deactivate")
    SeriesView deactivate(@PathVariable UUID id) {
        return series.deactivate(id);
    }
}
