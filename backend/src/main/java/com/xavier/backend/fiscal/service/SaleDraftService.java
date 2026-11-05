package com.xavier.backend.fiscal.service;

import com.xavier.backend.fiscal.exception.SaleDraftConflictException;
import com.xavier.backend.fiscal.exception.UnknownStoreException;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * tx-A of D50 §7.6: the SALE draft of an order or an ITEMS partition (D70), in the caller's (settlement's)
 * transaction. Idempotent per {@code (sourceType, sourceId)}: an existing DRAFT must match the command's lines and
 * totals and may take its buyer, customer and on-account mark ({@link FiscalDocument#amendDraft}); an ISSUED document
 * is returned unchanged. R2's {@code createSaleDraft} never amends. Not authorized here: the calling use case already
 * did (D27, D36).
 */
@Service
public class SaleDraftService {

    private final FiscalDocumentRepository documents;
    private final OrganizationApi organization;
    private final Clock clock;

    SaleDraftService(FiscalDocumentRepository documents, OrganizationApi organization, Clock clock) {
        this.documents = documents;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView createDraft(DraftCommand command) {
        return documents.findBySource(command.sourceType(), command.sourceId(), DocumentType.SALE)
                .map(existing -> amend(existing, command))
                .orElseGet(() -> draft(command));
    }

    /** R2 semantics, kept exactly: an existing document of the order, DRAFT or ISSUED, is returned unchanged. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView createSaleDraft(SaleDraftCommand command) {
        return documents.findBySource(SourceType.ORDER, command.orderId(), DocumentType.SALE)
                .map(FiscalDocument::toView)
                .orElseGet(() -> draft(command.toDraftCommand()));
    }

    private DocumentView amend(FiscalDocument document, DraftCommand command) {
        if (document.isIssued()) {
            return document.toView();
        }
        document.amendDraft(command, clock.instant());
        documents.flush(); // the returned version is the new one
        return document.toView();
    }

    private DocumentView draft(DraftCommand command) {
        StoreSummary store = organization.findStore(command.storeId())
                .orElseThrow(() -> new UnknownStoreException(command.storeId()));
        FiscalDocument document = FiscalDocument.draft(command, organization.companyProfile(), store,
                clock.instant());
        return UniqueConstraints.saveAndFlush(documents, document,
                () -> new SaleDraftConflictException(command.sourceId()), "fiscal_document_source_uk").toView();
    }
}
