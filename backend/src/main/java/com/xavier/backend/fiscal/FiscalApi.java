package com.xavier.backend.fiscal;

import com.xavier.backend.fiscal.model.DocumentStatus;
import com.xavier.backend.fiscal.model.DocumentType;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.fiscal.service.DocumentFactsService;
import com.xavier.backend.fiscal.service.DocumentIssueService;
import com.xavier.backend.fiscal.service.SaleDraftService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final DocumentFactsService facts;

    FiscalApi(SaleDraftService drafts, DocumentIssueService issuer, FiscalDocumentRepository documents,
              DocumentFactsService facts) {
        this.drafts = drafts;
        this.issuer = issuer;
        this.documents = documents;
        this.facts = facts;
    }

    /**
     * R2's tx-A (D50 §7.6), in the caller's transaction: the SALE draft of the order, with snapshots of company,
     * store and buyer: source {@code ORDER}, no business day, no customer, not on account. Idempotent per
     * {@code (ORDER, orderId)}: an existing document, DRAFT or ISSUED, is returned unchanged (its buyer is kept).
     *
     * <p>Lines follow D59 for tax-inclusive prices, exactly as orders computes them: {@code gross =
     * unitPrice.times(quantity)}, {@code net = gross.divide(1 + rate/100)} (HALF_UP to minor units),
     * {@code tax = gross − net}; the totals are the sums of the lines. See {@link
     * com.xavier.backend.fiscal.model.dto.DraftLine}.
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
     * tx-A (D50 §7.6), generalized (D66, D70): the SALE draft of an order ({@code ORDER}: the residual document at
     * close) or of an ITEMS partition ({@code PARTITION}), in the caller's transaction, with snapshots of company,
     * store and buyer. Idempotent per {@code (sourceType, sourceId)}: an existing DRAFT must have the command's lines
     * and totals; a non-null buyer or customer replaces the stored one (null keeps it); on account may be set but
     * never undone or moved to another customer. An ISSUED document is returned unchanged.
     * Lines follow D59 as described on {@link #createSaleDraft}.
     *
     * @throws com.xavier.backend.shared.exception.BusinessRuleException (422) for inconsistent lines or totals, a
     *         PARTITION without its order, an ORDER for another order, or on account without a customer
     * @throws com.xavier.backend.shared.exception.ConflictException (409) {@code DRAFT_MISMATCH} for other lines or
     *         totals or an undone on-account mark; uncoded when a concurrent draft won the race or the draft is stale
     * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView createDraft(DraftCommand cmd) {
        return drafts.createDraft(cmd);
    }

    /**
     * In the caller's transaction: a DRAFT that a cancelled credit sale had put on account (D74) becomes a paid sale
     * again: {@code onAccount = false}, no customer. Its buyer and lines stay. {@code createDraft}'s sticky rule is
     * unchanged otherwise.
     *
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown document
     * @throws com.xavier.backend.shared.exception.ConflictException (409) for an ISSUED document (never changed)
     * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DocumentView clearOnAccount(UUID documentId) {
        return drafts.clearOnAccount(documentId);
    }

    /**
     * tx-B (D50 §7.6) in its own transaction. Commit the draft first, then call issue with no transaction bound:
     * called inside one it throws {@link IllegalStateException} before doing anything. It numbers the draft from
     * its store's active series without gaps, issues it through the adapter and publishes
     * {@link com.xavier.backend.fiscal.model.event.FiscalDocumentIssued}. An ISSUED document is returned unchanged.
     * Runs as the current actor: its terminal is where the original prints. A failure's {@code lastError} is the
     * business reason, or only the failure type for a technical error (details in the server log).
     *
     * @throws FiscalIssueFailedException (422 {@code FISCAL_PENDING}) when issuing failed: no number was used and
     *         the draft keeps {@code lastError}, recorded in a separate transaction
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown document
     * @throws IllegalStateException when the caller has a transaction open
     */
    public DocumentView issue(UUID documentId, UUID actorEmployeeId) {
        return issuer.issue(documentId, actorEmployeeId);
    }

    @Transactional(readOnly = true)
    public Optional<DocumentView> saleDocumentFor(UUID orderId) {
        return documents.findBySource(SourceType.ORDER, orderId, DocumentType.SALE)
                .map(FiscalDocument::toView);
    }

    /**
     * D70 step 1: per source line, the quantity, gross and tax on the order's ISSUED and on its DRAFT documents
     * (partition, residual and R2 documents alike), and the DRAFT documents still to issue.
     */
    @Transactional(readOnly = true)
    public Coverage coverage(UUID orderId) {
        return facts.coverage(orderId);
    }

    /** Every DRAFT or ISSUED document of an order, whatever its source, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentView> documentsOfOrder(UUID orderId) {
        return facts.documentsOfOrder(orderId);
    }

    /**
     * For reporting (D84): the header, lines (with {@code sourceLineId}) and tax summary of one document. An R2
     * document's business day falls back to the day of its issue (D85).
     *
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown document
     */
    @Transactional(readOnly = true)
    public DocumentFacts documentFacts(UUID documentId) {
        return facts.documentFacts(documentId);
    }

    /**
     * For the reporting backfill (D84): the ids of a store's ISSUED documents whose business day is in
     * {@code [fromBusinessDate, toBusinessDate]} (both inclusive), oldest issue first.
     *
     * @throws com.xavier.backend.shared.exception.NotFoundException (404) for an unknown store
     */
    @Transactional(readOnly = true)
    public Page<UUID> issuedDocumentIds(UUID storeId, LocalDate fromBusinessDate, LocalDate toBusinessDate,
                                        Pageable page) {
        return facts.issuedDocumentIds(storeId, fromBusinessDate, toBusinessDate, page);
    }

    /** Fiscal pendencies of a store: drafts not issued yet (D50 §7.8 day close). */
    @Transactional(readOnly = true)
    public long countDrafts(UUID storeId) {
        return documents.countByStoreIdAndStatus(storeId, DocumentStatus.DRAFT);
    }
}
