package com.xavier.backend.fiscal.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.ReprintView;
import com.xavier.backend.fiscal.service.DocumentQueryService;
import com.xavier.backend.fiscal.service.ReprintService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POS documents of the session's store (D39): read and reprint. */
@RestController
@RequestMapping(PosDocumentController.PATH)
class PosDocumentController {

    static final String PATH = "/api/v1/pos/documents";

    private final DocumentQueryService documents;
    private final ReprintService reprints;
    private final IdempotentRequests idempotency;

    PosDocumentController(DocumentQueryService documents, ReprintService reprints, IdempotentRequests idempotency) {
        this.documents = documents;
        this.reprints = reprints;
        this.idempotency = idempotency;
    }

    @GetMapping("/{id}")
    DocumentView get(@PathVariable UUID id) {
        return documents.getForPos(id);
    }

    /** A copy of an issued document (spec §8.4); {@code Idempotency-Key} required (D47). */
    @PostMapping("/{id}/reprints")
    ResponseEntity<ReprintView> reprint(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                        HttpServletRequest request) {
        return idempotency.execute("fiscal.document.reprint", key, request, null, ReprintView.class,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(reprints.reprint(id)));
    }
}
