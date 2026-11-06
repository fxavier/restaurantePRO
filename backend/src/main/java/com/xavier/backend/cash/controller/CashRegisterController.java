package com.xavier.backend.cash.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.cash.model.dto.RegisterCreateRequest;
import com.xavier.backend.cash.model.dto.RegisterSummary;
import com.xavier.backend.cash.model.dto.RegisterUpdateRequest;
import com.xavier.backend.cash.service.CashRegisterService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CashRegisterController {

    static final String PATH = "/api/v1/backoffice/cash/registers";

    private final CashRegisterService registers;
    private final IdempotentRequests idempotent;

    CashRegisterController(CashRegisterService registers, IdempotentRequests idempotent) {
        this.registers = registers;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    PageResponse<RegisterSummary> list(@RequestParam UUID storeId, @PageableDefault(sort = "code") Pageable pageable) {
        return registers.list(storeId, pageable);
    }

    /** POS: the session store's active registers (CASH_OPERATE). */
    @GetMapping("/api/v1/pos/cash/registers")
    PageResponse<RegisterSummary> listForPos(@PageableDefault(sort = "code") Pageable pageable) {
        return registers.listForPos(pageable);
    }

    @PostMapping(PATH)
    ResponseEntity<RegisterSummary> create(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody RegisterCreateRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.register.create", key, request, body, RegisterSummary.class, () -> {
            RegisterSummary created = registers.create(body);
            return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
        });
    }

    @PutMapping(PATH + "/{id}")
    ResponseEntity<RegisterSummary> update(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody RegisterUpdateRequest body, HttpServletRequest request) {
        return idempotent.execute("cash.register.update", key, request, body, RegisterSummary.class,
                () -> ResponseEntity.ok(registers.update(id, body)));
    }
}
