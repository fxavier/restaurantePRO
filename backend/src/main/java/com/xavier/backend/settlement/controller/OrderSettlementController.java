package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.settlement.model.dto.CloseOrderRequest;
import com.xavier.backend.settlement.model.dto.IssueDocumentRequest;
import com.xavier.backend.settlement.model.dto.OrderSettlementView;
import com.xavier.backend.settlement.service.OrderSettlementService;
import com.xavier.backend.settlement.service.SettlementQueryService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * An order's settlement on the POS (PRD S16, D37): its bill, its sale document and its close. The document and the
 * close are D63 multi-step commands ({@code executeSteps}), because issuing runs with no transaction (D55).
 */
@RestController
class OrderSettlementController {

    static final String ORDERS = "/api/v1/pos/orders";
    static final String DOCUMENTS = "/api/v1/pos/documents";

    private final SettlementQueryService queries;
    private final OrderSettlementService settlement;
    private final Idempotent idempotent;

    OrderSettlementController(SettlementQueryService queries, OrderSettlementService settlement,
                              IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.queries = queries;
        this.settlement = settlement;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @GetMapping(ORDERS + "/{id}/settlement")
    OrderSettlementView settlement(@PathVariable UUID id) {
        return queries.settlement(id);
    }

    /** 201 with the ISSUED document; 422 {@code FISCAL_PENDING} {documentId} is not stored, so a retry issues it. */
    @PostMapping(ORDERS + "/{id}/documents")
    ResponseEntity<DocumentView> issue(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody IssueDocumentRequest body, HttpServletRequest request) {
        return idempotent.steps("settlement.order.document", key, request, body, DocumentView.class, () -> {
            DocumentView issued = settlement.issueDocument(id, body);
            return ResponseEntity.created(URI.create(DOCUMENTS + "/" + issued.id())).body(issued);
        });
    }

    /** 200 with the CLOSED order's settlement view; an order already CLOSED answers with its view. */
    @PostMapping(ORDERS + "/{id}/close")
    ResponseEntity<OrderSettlementView> close(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody CloseOrderRequest body,
                                              HttpServletRequest request) {
        return idempotent.steps("settlement.order.close", key, request, body, OrderSettlementView.class,
                () -> ResponseEntity.ok(settlement.close(id, body)));
    }
}
