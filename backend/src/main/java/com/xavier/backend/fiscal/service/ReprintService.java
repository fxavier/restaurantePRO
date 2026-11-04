package com.xavier.backend.fiscal.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.adapter.ActiveFiscalAdapter;
import com.xavier.backend.fiscal.exception.DocumentNotFoundException;
import com.xavier.backend.fiscal.exception.ReprintConflictException;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.FiscalDocumentPrint;
import com.xavier.backend.fiscal.model.dto.ReprintView;
import com.xavier.backend.fiscal.model.event.FiscalDocumentReprintRequested;
import com.xavier.backend.fiscal.repository.FiscalDocumentPrintRepository;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A reprint is an audited copy (spec §8.4): the next copy number and a {@link FiscalDocumentReprintRequested} for
 * production. It creates no document, payment or cash movement.
 */
@Service
public class ReprintService {

    private final FiscalDocumentRepository documents;
    private final FiscalDocumentPrintRepository prints;
    private final DocumentQueryService queries;
    private final ActiveFiscalAdapter adapter;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ReprintService(FiscalDocumentRepository documents, FiscalDocumentPrintRepository prints,
                   DocumentQueryService queries, ActiveFiscalAdapter adapter, AccessPolicy accessPolicy,
                   AuditLog auditLog, ApplicationEventPublisher events, Clock clock) {
        this.documents = documents;
        this.prints = prints;
        this.queries = queries;
        this.adapter = adapter;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public ReprintView reprint(UUID documentId) {
        PosContext pos = accessPolicy.requirePos(Permission.FISCAL_DOCUMENT_REPRINT);
        DocumentQueryService.requireSessionStore(pos, queries.storeOf(documentId), documentId);
        // the document lock serializes reprints of one document, so the next copy number is free
        FiscalDocument document = documents.lockById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        IssuedDocument printable = document.toIssuedDocument(); // 409 for a draft
        int copyNo = prints.lastCopyNo(documentId) + 1;
        Instant now = clock.instant();
        UniqueConstraints.saveAndFlush(prints,
                FiscalDocumentPrint.copy(documentId, copyNo, now, pos.employeeId(), pos.terminalId()),
                () -> new ReprintConflictException(documentId), "fiscal_document_print_copy_uk");
        events.publishEvent(new FiscalDocumentReprintRequested(documentId, copyNo, document.getStoreId(),
                pos.terminalId(), adapter.printout(printable, true)));
        auditLog.recordAsCurrentActor("FISCAL_DOCUMENT_REPRINTED", "FiscalDocument", documentId.toString(),
                document.getStoreId(), null, null, Map.of("copyNo", copyNo, "number", printable.number(),
                        "seriesCode", printable.seriesCode()));
        return new ReprintView(documentId, copyNo, now);
    }
}
