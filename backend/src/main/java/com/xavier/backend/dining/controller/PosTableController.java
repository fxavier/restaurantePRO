package com.xavier.backend.dining.controller;

import com.xavier.backend.dining.model.dto.TableMap;
import com.xavier.backend.dining.service.TableMapService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PosTableController {

    private final TableMapService tableMap;

    PosTableController(TableMapService tableMap) {
        this.tableMap = tableMap;
    }

    @GetMapping("/api/v1/pos/tables")
    TableMap tables(@RequestParam UUID storeId) {
        return tableMap.tableMap(storeId);
    }
}
