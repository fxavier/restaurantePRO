package com.xavier.backend.fiscal.service;

import com.xavier.backend.fiscal.exception.SaleDraftConflictException;
import com.xavier.backend.fiscal.exception.UnknownStoreException;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
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
 * transaction. Idempotent per {@code (sourceType, sourceId)}: an existing DRAFT takes the command's buyer, customer
 * and on-account mark (its lines and totals stay as drafted), and an ISSUED document is returned unchanged. Not
 * authorized here: the calling use case already did (D27, D36).
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

    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView createSaleDraft(SaleDraftCommand command) {
        return createDraft(command.toDraftCommand());
    }

    private DocumentView amend(FiscalDocument document, DraftCommand command) {
        if (document.isIssued()) {
            return document.toView();
        }
        document.amendDraft(command.buyer(), command.customerId(), command.onAccount(), clock.instant());
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
