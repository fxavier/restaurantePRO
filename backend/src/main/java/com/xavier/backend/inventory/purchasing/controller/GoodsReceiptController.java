package com.xavier.backend.inventory.purchasing.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptRequest;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptUpdateRequest;
import com.xavier.backend.inventory.purchasing.model.dto.ReceiptView;
import com.xavier.backend.inventory.purchasing.service.GoodsReceiptService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goods receipts (D125). Every command but the versioned edit takes an Idempotency-Key: a confirm replayed with its
 * key answers the stored response and posts nothing again.
 */
@RestController
class GoodsReceiptController {

    static final String PATH = PurchasingPaths.BACKOFFICE + "/receipts";

    private final GoodsReceiptService receipts;
    private final IdempotentRequests idempotent;

    GoodsReceiptController(GoodsReceiptService receipts, IdempotentRequests idempotent) {
        this.receipts = receipts;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<ReceiptView> list(@RequestParam UUID storeId, Pageable pageable) {
        return receipts.list(storeId, pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<ReceiptView> create(@RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody ReceiptRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.receipt.create", key, request, body, ReceiptView.class, () -> {
            ReceiptView created = receipts.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PATH + "/{id}")
    ReceiptView get(@PathVariable UUID id) {
        return receipts.get(id);
    }

    @PutMapping(PATH + "/{id}")
    ReceiptView update(@PathVariable UUID id, @Valid @RequestBody ReceiptUpdateRequest body) {
        return receipts.update(id, body);
    }

    @PostMapping(PATH + "/{id}/confirm")
    ResponseEntity<ReceiptView> confirm(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        HttpServletRequest request) {
        return idempotent.execute("inventory.receipt.confirm", key, request, Map.of(), ReceiptView.class,
                () -> ResponseEntity.ok(receipts.confirm(id)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<ReceiptView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       HttpServletRequest request) {
        return idempotent.execute("inventory.receipt.cancel", key, request, Map.of(), ReceiptView.class,
                () -> ResponseEntity.ok(receipts.cancel(id)));
    }
}
