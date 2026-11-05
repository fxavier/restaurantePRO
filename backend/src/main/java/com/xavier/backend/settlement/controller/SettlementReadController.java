package com.xavier.backend.settlement.controller;

import com.xavier.backend.settlement.model.dto.OpenOrderBalance;
import com.xavier.backend.settlement.model.dto.PendingView;
import com.xavier.backend.settlement.service.SettlementQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The table map's balances (POS) and the store's settlement pendings (BackOffice). */
@RestController
class SettlementReadController {

    private final SettlementQueryService queries;

    SettlementReadController(SettlementQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/api/v1/pos/settlement/open-orders")
    List<OpenOrderBalance> openOrders() {
        return queries.openOrders();
    }

    @GetMapping("/api/v1/backoffice/settlement/pending")
    PendingView pending(@RequestParam UUID storeId) {
        return queries.pending(storeId);
    }
}
