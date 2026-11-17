package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.PosCatalogResponse;
import com.xavier.backend.catalog.service.PublishedCatalogService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
class PosCatalogController {

    private final PublishedCatalogService publishedCatalog;
    private final JsonMapper json;

    PosCatalogController(PublishedCatalogService publishedCatalog, JsonMapper json) {
        this.publishedCatalog = publishedCatalog;
        this.json = json;
    }

    /** The snapshot is parsed here, after the read's transaction ended (S77); the bytes are as before. */
    @GetMapping("/api/v1/pos/catalog")
    PosCatalogResponse current(@RequestParam(required = false) UUID storeId) {
        PublishedCatalogService.Published published = publishedCatalog.current(storeId);
        return new PosCatalogResponse(published.releaseId(), published.storeId(), published.version(),
                published.effectiveAt(), json.readTree(published.snapshot()));
    }
}
