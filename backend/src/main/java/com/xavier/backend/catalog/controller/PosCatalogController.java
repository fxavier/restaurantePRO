package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.PosCatalogResponse;
import com.xavier.backend.catalog.service.PublishedCatalogService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PosCatalogController {

    private final PublishedCatalogService publishedCatalog;

    PosCatalogController(PublishedCatalogService publishedCatalog) {
        this.publishedCatalog = publishedCatalog;
    }

    @GetMapping("/api/v1/pos/catalog")
    PosCatalogResponse current(@RequestParam UUID storeId) {
        return publishedCatalog.current(storeId);
    }
}
