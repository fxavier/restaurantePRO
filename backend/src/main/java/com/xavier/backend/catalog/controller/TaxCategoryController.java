package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.TaxCategoryCreateRequest;
import com.xavier.backend.catalog.model.dto.TaxCategorySummary;
import com.xavier.backend.catalog.model.dto.TaxCategoryUpdateRequest;
import com.xavier.backend.catalog.model.dto.TaxRateCreateRequest;
import com.xavier.backend.catalog.model.dto.TaxRateSummary;
import com.xavier.backend.catalog.service.TaxService;
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
@RequestMapping(TaxCategoryController.PATH)
class TaxCategoryController {

    static final String PATH = "/api/v1/backoffice/catalog/tax-categories";

    private final TaxService tax;

    TaxCategoryController(TaxService tax) {
        this.tax = tax;
    }

    @GetMapping
    PageResponse<TaxCategorySummary> list(@PageableDefault(sort = "code") Pageable pageable) {
        return tax.list(pageable);
    }

    @GetMapping("/{id}")
    TaxCategorySummary get(@PathVariable UUID id) {
        return tax.get(id);
    }

    @PostMapping
    ResponseEntity<TaxCategorySummary> create(@Valid @RequestBody TaxCategoryCreateRequest request) {
        TaxCategorySummary created = tax.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    TaxCategorySummary update(@PathVariable UUID id, @Valid @RequestBody TaxCategoryUpdateRequest request) {
        return tax.update(id, request);
    }

    @PostMapping("/{id}/rates")
    ResponseEntity<TaxRateSummary> addRate(@PathVariable UUID id, @Valid @RequestBody TaxRateCreateRequest request) {
        TaxRateSummary created = tax.addRate(id, request);
        return ResponseEntity.created(URI.create(PATH + "/" + id)).body(created);
    }
}
