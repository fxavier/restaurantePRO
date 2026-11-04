package com.xavier.backend.fiscal.service;

import com.xavier.backend.fiscal.exception.SaleDraftConflictException;
import com.xavier.backend.fiscal.exception.UnknownStoreException;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.SourceType;
import com.xavier.backend.fiscal.model.dto.DocumentView;
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
 * tx-A of D50 §7.6: the SALE draft of an order, in the caller's (settlement's) transaction. Idempotent per
 * {@code (ORDER, orderId)}: an existing DRAFT or ISSUED document is returned as it is. Not authorized here: the
 * calling use case already did (D27, D36).
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
    public DocumentView createSaleDraft(SaleDraftCommand command) {
        return documents.findBySourceTypeAndSourceIdAndDocumentType(SourceType.ORDER, command.orderId(),
                        DocumentType.SALE)
                .map(FiscalDocument::toView)
                .orElseGet(() -> draft(command));
    }

    private DocumentView draft(SaleDraftCommand command) {
        StoreSummary store = organization.findStore(command.storeId())
                .orElseThrow(() -> new UnknownStoreException(command.storeId()));
        FiscalDocument document = FiscalDocument.saleDraft(command, organization.companyProfile(), store,
                clock.instant());
        return UniqueConstraints.saveAndFlush(documents, document,
                () -> new SaleDraftConflictException(command.orderId()), "fiscal_document_source_uk").toView();
    }
}
