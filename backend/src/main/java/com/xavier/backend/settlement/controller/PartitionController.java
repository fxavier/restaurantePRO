package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.settlement.model.dto.CancelPartitionRequest;
import com.xavier.backend.settlement.model.dto.CreatePartitionRequest;
import com.xavier.backend.settlement.model.dto.EqualPartsRequest;
import com.xavier.backend.settlement.model.dto.IssueDocumentRequest;
import com.xavier.backend.settlement.model.dto.PartitionView;
import com.xavier.backend.settlement.service.PartitionDocumentService;
import com.xavier.backend.settlement.service.PartitionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Bill partitions on the POS (PRD S28, D67, D70): create, equal parts, list, cancel, and a partition's document. */
@RestController
class PartitionController {

    static final String ORDERS = OrderSettlementController.ORDERS;
    static final String PARTITIONS = "/api/v1/pos/partitions";

    private final PartitionService partitions;
    private final PartitionDocumentService documents;
    private final IdempotentRequests idempotent;

    PartitionController(PartitionService partitions, PartitionDocumentService documents,
                        IdempotentRequests idempotent) {
        this.partitions = partitions;
        this.documents = documents;
        this.idempotent = idempotent;
    }

    @PostMapping(ORDERS + "/{id}/partitions")
    ResponseEntity<PartitionView> create(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CreatePartitionRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.partition.create", key, request, body, PartitionView.class, () -> {
            PartitionView created = partitions.create(id, body);
            return ResponseEntity.created(URI.create(PARTITIONS + "/" + created.id())).body(created);
        });
    }

    @PostMapping(ORDERS + "/{id}/partitions/equal")
    ResponseEntity<PartitionView[]> equal(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody EqualPartsRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.partition.equal", key, request, body, PartitionView[].class,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(partitions.equalParts(id, body).toArray(PartitionView[]::new)));
    }

    @GetMapping(ORDERS + "/{id}/partitions")
    List<PartitionView> list(@PathVariable UUID id) {
        return partitions.list(id);
    }

    @PostMapping(PARTITIONS + "/{id}/cancel")
    ResponseEntity<PartitionView> cancel(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody CancelPartitionRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.partition.cancel", key, request, body, PartitionView.class,
                () -> ResponseEntity.ok(partitions.cancel(id, body)));
    }

    /** D70, D63: s1 lock + the PARTITION draft, s2 issue; 422 {@code FISCAL_PENDING} {documentId} is retried. */
    @PostMapping(PARTITIONS + "/{id}/documents")
    ResponseEntity<DocumentView> document(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody IssueDocumentRequest body, HttpServletRequest request) {
        return idempotent.steps("settlement.partition.document", key, request, body, DocumentView.class, () -> {
            DocumentView issued = documents.issue(id, body);
            return ResponseEntity.created(URI.create(OrderSettlementController.DOCUMENTS + "/" + issued.id()))
                    .body(issued);
        });
    }
}
