package com.xavier.backend.fiscal.controller;

import com.xavier.backend.fiscal.model.dto.DocumentSummary;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.service.DocumentQueryService;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice document reads ({@code FISCAL_DOCUMENT_VIEW} in the document's store). */
@RestController
@RequestMapping("/api/v1/backoffice/fiscal/documents")
class DocumentController {

    private final DocumentQueryService documents;

    DocumentController(DocumentQueryService documents) {
        this.documents = documents;
    }

    @GetMapping
    PageResponse<DocumentSummary> list(@RequestParam UUID storeId, @RequestParam(required = false) Instant from,
                                       @RequestParam(required = false) Instant to,
                                       @PageableDefault(sort = "issuedAt", direction = Sort.Direction.DESC)
                                       Pageable pageable) {
        return documents.list(storeId, from, to, pageable);
    }

    @GetMapping("/{id}")
    DocumentView get(@PathVariable UUID id) {
        return documents.get(id);
    }
}
