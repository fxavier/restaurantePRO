package com.xavier.backend.fiscal.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.exception.DocumentNotFoundException;
import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.DocumentSummary;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reading documents (BackOffice per store, POS in the session's store), with the D42 check order. */
@Service
@Transactional(readOnly = true)
public class DocumentQueryService {

    /** Open window ends: an issued document is never before the epoch nor after this. */
    private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
    private static final Instant NO_UPPER_BOUND = Instant.parse("9999-12-31T00:00:00Z");

    private final FiscalDocumentRepository documents;
    private final AccessPolicy accessPolicy;

    DocumentQueryService(FiscalDocumentRepository documents, AccessPolicy accessPolicy) {
        this.documents = documents;
        this.accessPolicy = accessPolicy;
    }

    /** ISSUED documents of {@code storeId} with {@code from <= issuedAt < to}; either bound may be {@code null}. */
    public PageResponse<DocumentSummary> list(UUID storeId, Instant from, Instant to, Pageable pageable) {
        accessPolicy.require(Permission.FISCAL_DOCUMENT_VIEW, storeId);
        return PageResponse.from(documents.findByStoreIdAndStatusAndIssuedAtGreaterThanEqualAndIssuedAtLessThan(
                        storeId, DocumentStatus.ISSUED, from == null ? NO_LOWER_BOUND : from,
                        to == null ? NO_UPPER_BOUND : to, pageable)
                .map(FiscalDocument::toSummary));
    }

    public DocumentView get(UUID id) {
        accessPolicy.require(Permission.FISCAL_DOCUMENT_VIEW, storeOf(id));
        return load(id).toView();
    }

    public DocumentView getForPos(UUID id) {
        PosContext pos = accessPolicy.requirePos(Permission.FISCAL_DOCUMENT_VIEW);
        requireSessionStore(pos, storeOf(id), id);
        return load(id).toView();
    }

    UUID storeOf(UUID id) {
        return documents.findStoreIdById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    static void requireSessionStore(PosContext pos, UUID documentStore, UUID id) {
        if (!documentStore.equals(pos.storeId())) {
            throw new AccessDeniedException("Document " + id + " belongs to another store");
        }
    }

    private FiscalDocument load(UUID id) {
        return documents.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }
}
