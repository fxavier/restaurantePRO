package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.SourceType;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.fiscal.service.DocumentIssueService;
import com.xavier.backend.fiscal.service.SaleDraftService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What settlement asks the fiscal module (D36, D55). It does not authorize: the calling use case already did (D27).
 */
@Service
public class FiscalApi {

    private final SaleDraftService drafts;
    private final DocumentIssueService issuer;
    private final FiscalDocumentRepository documents;

    FiscalApi(SaleDraftService drafts, DocumentIssueService issuer, FiscalDocumentRepository documents) {
        this.drafts = drafts;
        this.issuer = issuer;
        this.documents = documents;
    }

    /**
     * tx-A (D50 §7.6), in the caller's transaction: the SALE draft of the order, with snapshots of company, store
     * and buyer. Idempotent per {@code (ORDER, orderId)}: returns the existing document, DRAFT or ISSUED.
     *
     * @throws com.xavier.backend.shared.exception.BusinessRuleException (422) for inconsistent lines or totals
     * @throws com.xavier.backend.shared.exception.ConflictException (409) when a concurrent draft won the race
     * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView createSaleDraft(SaleDraftCommand cmd) {
        return drafts.createSaleDraft(cmd);
    }

    /**
     * tx-B (D50 §7.6) in a new transaction ({@code REQUIRES_NEW}; the caller's, if any, is suspended, so commit the
     * draft first): numbers the draft from its store's active series without gaps, issues it through the adapter and
     * publishes {@link com.xavier.backend.fiscal.model.event.FiscalDocumentIssued}. An ISSUED document is returned
     * unchanged. Runs as the current actor: its terminal is where the original prints.
     *
     * @throws FiscalIssueFailedException (422 {@code FISCAL_PENDING}) when issuing failed: no number was used and
     *         the draft keeps {@code lastError}, recorded in a separate transaction
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown document
     */
    public DocumentView issue(UUID documentId, UUID actorEmployeeId) {
        return issuer.issue(documentId, actorEmployeeId);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentView> saleDocumentFor(UUID orderId) {
        return documents.findBySource(SourceType.ORDER, orderId, DocumentType.SALE)
                .map(FiscalDocument::toView);
    }

    /** Fiscal pendencies of a store: drafts not issued yet (D50 §7.8 day close). */
    @Transactional(readOnly = true)
    public long countDrafts(UUID storeId) {
        return documents.countByStoreIdAndStatus(storeId, DocumentStatus.DRAFT);
    }
}
