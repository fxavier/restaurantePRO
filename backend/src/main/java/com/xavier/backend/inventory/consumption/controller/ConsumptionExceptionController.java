package com.xavier.backend.inventory.consumption.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.consumption.model.dto.ExceptionView;
import com.xavier.backend.inventory.consumption.model.dto.ReprocessView;
import com.xavier.backend.inventory.consumption.service.ConsumptionExceptionService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Consumption exceptions (D116): the list ({@code INVENTORY_VIEW}) and the reprocess ({@code INVENTORY_REBUILD}). */
@RestController
class ConsumptionExceptionController {

    static final String PATH = "/api/v1/backoffice/inventory/consumption-exceptions";

    private final ConsumptionExceptionService exceptions;
    private final IdempotentRequests idempotent;

    ConsumptionExceptionController(ConsumptionExceptionService exceptions, IdempotentRequests idempotent) {
        this.exceptions = exceptions;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<ExceptionView> list(@RequestParam UUID storeId,
                                     @RequestParam(defaultValue = "false") boolean open, Pageable pageable) {
        return exceptions.list(storeId, open, pageable);
    }

    @PostMapping(PATH + "/{id}/reprocess")
    ResponseEntity<ReprocessView> reprocess(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            HttpServletRequest request) {
        return idempotent.execute("inventory.consumption-exception.reprocess", key, request, id.toString(),
                ReprocessView.class, () -> ResponseEntity.ok(exceptions.reprocess(id)));
    }
}
