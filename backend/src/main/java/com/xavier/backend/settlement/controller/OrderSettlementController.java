package com.xavier.backend.settlement.controller;

import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.service.SettlementQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** An order's settlement on the POS: the bill, and later its document and close (PRD S16, D37). */
@RestController
class OrderSettlementController {

    static final String ORDERS = "/api/v1/pos/orders";

    private final SettlementQueryService queries;

    OrderSettlementController(SettlementQueryService queries) {
        this.queries = queries;
    }

    @GetMapping(ORDERS + "/{id}/settlement")
    OrderSettlementView settlement(@PathVariable UUID id) {
        return queries.settlement(id);
    }
}
