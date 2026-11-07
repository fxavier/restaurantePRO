package com.xavier.backend.inventory.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.model.dto.ReceiveRequest;
import com.xavier.backend.inventory.model.dto.TransferRequest;
import com.xavier.backend.inventory.model.dto.TransferUpdateRequest;
import com.xavier.backend.inventory.model.dto.TransferView;
import com.xavier.backend.inventory.service.TransferService;
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

/** Stock transfers (D120). Every command but the versioned edit takes an Idempotency-Key. */
@RestController
class TransferController {

    static final String PATH = InventoryPaths.BACKOFFICE + "/transfers";

    private final TransferService transfers;
    private final IdempotentRequests idempotent;

    TransferController(TransferService transfers, IdempotentRequests idempotent) {
        this.transfers = transfers;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<TransferView> list(@RequestParam UUID storeId, Pageable pageable) {
        return transfers.list(storeId, pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<TransferView> create(@RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody TransferRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.transfer.create", key, request, body, TransferView.class, () -> {
            TransferView created = transfers.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PATH + "/{id}")
    TransferView get(@PathVariable UUID id) {
        return transfers.get(id);
    }

    @PutMapping(PATH + "/{id}")
    TransferView update(@PathVariable UUID id, @Valid @RequestBody TransferUpdateRequest body) {
        return transfers.update(id, body);
    }

    @PostMapping(PATH + "/{id}/ship")
    ResponseEntity<TransferView> ship(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      HttpServletRequest request) {
        return idempotent.execute("inventory.transfer.ship", key, request, Map.of(), TransferView.class,
                () -> ResponseEntity.ok(transfers.ship(id)));
    }

    @PostMapping(PATH + "/{id}/receive")
    ResponseEntity<TransferView> receive(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody ReceiveRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.transfer.receive", key, request, body, TransferView.class,
                () -> ResponseEntity.ok(transfers.receive(id, body)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<TransferView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        HttpServletRequest request) {
        return idempotent.execute("inventory.transfer.cancel", key, request, Map.of(), TransferView.class,
                () -> ResponseEntity.ok(transfers.cancel(id)));
    }
}
