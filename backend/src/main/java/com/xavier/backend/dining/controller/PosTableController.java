package com.xavier.backend.dining.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.dining.model.dto.TableLockRequest;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.service.TableLockService;
import com.xavier.backend.dining.service.TableMapService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PosTableController {

    static final String PATH = "/api/v1/pos/tables";

    private final TableMapService tableMap;
    private final TableLockService locks;
    private final IdempotencyService idempotency;
    private final AccessPolicy accessPolicy;

    PosTableController(TableMapService tableMap, TableLockService locks, IdempotencyService idempotency,
                       AccessPolicy accessPolicy) {
        this.tableMap = tableMap;
        this.locks = locks;
        this.idempotency = idempotency;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping(PATH)
    TableMap tables(@RequestParam UUID storeId) {
        return tableMap.tableMap(storeId);
    }

    @PostMapping(PATH + "/{id}/lock")
    ResponseEntity<TableMap.Table> lock(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody TableLockRequest body, HttpServletRequest request) {
        return idempotency.execute(accessPolicy.current().idempotencyScope("dining.table.lock"), key, target(request),
                body, TableMap.Table.class, () -> ResponseEntity.ok(locks.lock(id, body.reason())));
    }

    @PostMapping(PATH + "/{id}/unlock")
    ResponseEntity<TableMap.Table> unlock(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                          HttpServletRequest request) {
        return idempotency.execute(accessPolicy.current().idempotencyScope("dining.table.unlock"), key,
                target(request), null, TableMap.Table.class, () -> ResponseEntity.ok(locks.unlock(id)));
    }

    private static String target(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
