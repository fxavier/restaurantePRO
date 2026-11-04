package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.BarcodeCreateRequest;
import com.xavier.backend.catalog.model.dto.BarcodeSummary;
import com.xavier.backend.catalog.model.dto.BarcodeUpdateRequest;
import com.xavier.backend.catalog.service.BarcodeService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(BarcodeController.PATH)
class BarcodeController {

    static final String PATH = "/api/v1/backoffice/catalog/barcodes";

    private final BarcodeService barcodes;

    BarcodeController(BarcodeService barcodes) {
        this.barcodes = barcodes;
    }

    @GetMapping
    List<BarcodeSummary> listForItem(@RequestParam UUID itemId) {
        return barcodes.listForItem(itemId);
    }

    @GetMapping("/{id}")
    BarcodeSummary get(@PathVariable UUID id) {
        return barcodes.get(id);
    }

    @PostMapping
    ResponseEntity<BarcodeSummary> create(@Valid @RequestBody BarcodeCreateRequest request) {
        BarcodeSummary created = barcodes.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    BarcodeSummary update(@PathVariable UUID id, @Valid @RequestBody BarcodeUpdateRequest request) {
        return barcodes.update(id, request);
    }
}
