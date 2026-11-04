package com.xavier.backend.catalog.controller;

import com.xavier.backend.catalog.model.dto.ItemPriceCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemPriceSummary;
import com.xavier.backend.catalog.model.dto.ItemPriceUpdateRequest;
import com.xavier.backend.catalog.model.dto.PriceListCreateRequest;
import com.xavier.backend.catalog.model.dto.PriceListSummary;
import com.xavier.backend.catalog.model.dto.PriceListUpdateRequest;
import com.xavier.backend.catalog.service.PriceListService;
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
@RequestMapping(PriceListController.PATH)
class PriceListController {

    static final String PATH = "/api/v1/backoffice/catalog/price-lists";

    private final PriceListService priceLists;

    PriceListController(PriceListService priceLists) {
        this.priceLists = priceLists;
    }

    @GetMapping
    List<PriceListSummary> listForStore(@RequestParam UUID storeId) {
        return priceLists.listForStore(storeId);
    }

    @GetMapping("/{id}")
    PriceListSummary get(@PathVariable UUID id) {
        return priceLists.get(id);
    }

    @PostMapping
    ResponseEntity<PriceListSummary> create(@Valid @RequestBody PriceListCreateRequest request) {
        PriceListSummary created = priceLists.create(request);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    PriceListSummary rename(@PathVariable UUID id, @Valid @RequestBody PriceListUpdateRequest request) {
        return priceLists.rename(id, request);
    }

    @GetMapping("/{id}/prices")
    List<ItemPriceSummary> listPrices(@PathVariable UUID id) {
        return priceLists.listPrices(id);
    }

    @PostMapping("/{id}/prices")
    ResponseEntity<ItemPriceSummary> addPrice(@PathVariable UUID id, @Valid @RequestBody ItemPriceCreateRequest request) {
        ItemPriceSummary created = priceLists.addPrice(id, request);
        return ResponseEntity.created(URI.create(PATH + "/" + id + "/prices/" + created.id())).body(created);
    }

    @PutMapping("/{id}/prices/{priceId}")
    ItemPriceSummary changePrice(@PathVariable UUID id, @PathVariable UUID priceId,
                                 @Valid @RequestBody ItemPriceUpdateRequest request) {
        return priceLists.changePrice(id, priceId, request);
    }
}
