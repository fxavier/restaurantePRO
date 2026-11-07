package com.xavier.backend.inventory.count.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.count.model.dto.CountLineRequest;
import com.xavier.backend.inventory.count.model.dto.CountRequest;
import com.xavier.backend.inventory.count.model.dto.CountView;
import com.xavier.backend.inventory.count.model.dto.RejectCountRequest;
import com.xavier.backend.inventory.count.service.CountApprovalService;
import com.xavier.backend.inventory.count.service.StockCountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Stock counts (D119, D122). Every command but recording a line takes an Idempotency-Key. */
@RestController
class StockCountController {

    static final String PATH = "/api/v1/backoffice/inventory/counts";

    private final StockCountService counts;
    private final CountApprovalService approvals;
    private final IdempotentRequests idempotent;

    StockCountController(StockCountService counts, CountApprovalService approvals, IdempotentRequests idempotent) {
        this.counts = counts;
        this.approvals = approvals;
        this.idempotent = idempotent;
    }

    @PostMapping(PATH)
    ResponseEntity<CountView> create(@RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CountRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.count.create", key, request, body, CountView.class, () -> {
            CountView created = counts.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PATH + "/{id}")
    CountView get(@PathVariable UUID id) {
        return counts.get(id);
    }

    @PostMapping(PATH + "/{id}/start")
    ResponseEntity<CountView> start(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                    HttpServletRequest request) {
        return idempotent.execute("inventory.count.start", key, request, Map.of(), CountView.class,
                () -> ResponseEntity.ok(counts.start(id)));
    }

    @PutMapping(PATH + "/{id}/lines/{stockItemId}")
    CountView recordLine(@PathVariable UUID id, @PathVariable UUID stockItemId,
                         @Valid @RequestBody CountLineRequest body) {
        return counts.recordLine(id, stockItemId, body);
    }

    @PostMapping(PATH + "/{id}/submit")
    ResponseEntity<CountView> submit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     HttpServletRequest request) {
        return idempotent.execute("inventory.count.submit", key, request, Map.of(), CountView.class,
                () -> ResponseEntity.ok(counts.submit(id)));
    }

    @PostMapping(PATH + "/{id}/approve")
    ResponseEntity<CountView> approve(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                      HttpServletRequest request) {
        return idempotent.execute("inventory.count.approve", key, request, Map.of(), CountView.class,
                () -> ResponseEntity.ok(approvals.approve(id)));
    }

    @PostMapping(PATH + "/{id}/reject")
    ResponseEntity<CountView> reject(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody RejectCountRequest body, HttpServletRequest request) {
        return idempotent.execute("inventory.count.reject", key, request, body, CountView.class,
                () -> ResponseEntity.ok(counts.reject(id, body)));
    }

    @PostMapping(PATH + "/{id}/cancel")
    ResponseEntity<CountView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     HttpServletRequest request) {
        return idempotent.execute("inventory.count.cancel", key, request, Map.of(), CountView.class,
                () -> ResponseEntity.ok(counts.cancel(id)));
    }
}
