package com.xavier.backend.dining.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.dining.model.dto.TableLockRequest;
import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.service.TableLockService;
import com.xavier.backend.dining.service.TableMapService;
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
    private final IdempotentRequests idempotency;

    PosTableController(TableMapService tableMap, TableLockService locks, IdempotentRequests idempotency) {
        this.tableMap = tableMap;
        this.locks = locks;
        this.idempotency = idempotency;
    }

    @GetMapping(PATH)
    TableMap tables(@RequestParam(required = false) UUID storeId) {
        return tableMap.tableMap(storeId);
    }

    @PostMapping(PATH + "/{id}/lock")
    ResponseEntity<TableMap.Table> lock(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody TableLockRequest body, HttpServletRequest request) {
        return idempotency.execute("dining.table.lock", key, request, body, TableMap.Table.class,
                () -> ResponseEntity.ok(locks.lock(id, body.reason())));
    }

    @PostMapping(PATH + "/{id}/unlock")
    ResponseEntity<TableMap.Table> unlock(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                          HttpServletRequest request) {
        return idempotency.execute("dining.table.unlock", key, request, null, TableMap.Table.class,
                () -> ResponseEntity.ok(locks.unlock(id)));
    }

}
