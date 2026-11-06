package com.xavier.backend.fiscal.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.fiscal.FiscalIssueFailedException;
import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.adapter.ActiveFiscalAdapter;
import com.xavier.backend.fiscal.exception.DocumentNotFoundException;
import com.xavier.backend.fiscal.exception.NoActiveSeriesException;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.FiscalSeries;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.fiscal.repository.FiscalSeriesRepository;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * tx-B of D50 §7.6. In its own transaction: document {@code FOR UPDATE} → series {@code FOR UPDATE} →
 * {@code number = next_number}, {@code next_number + 1} → adapter (local only) → ISSUED → {@link FiscalDocumentIssued}.
 * The number exists only in the committing transaction, so a rollback leaves no gap. On failure a second, separate
 * transaction records {@code last_error} on the draft, and {@link FiscalIssueFailedException} (422
 * {@code FISCAL_PENDING}) is thrown.
 */
@Service
public class DocumentIssueService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIssueService.class);

    private final FiscalDocumentRepository documents;
    private final FiscalSeriesRepository series;
    private final ActiveFiscalAdapter adapter;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate inNewTransaction;

    DocumentIssueService(FiscalDocumentRepository documents, FiscalSeriesRepository series,
                         ActiveFiscalAdapter adapter, AccessPolicy accessPolicy, ApplicationEventPublisher events,
                         Clock clock, PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.series = series;
        this.adapter = adapter;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.clock = clock;
        this.inNewTransaction = new TransactionTemplate(transactionManager);
        this.inNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Issues as the current actor; its terminal is where the original prints. Already ISSUED → returned as is. */
    public DocumentView issue(UUID documentId, UUID actorEmployeeId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // REQUIRES_NEW would not see the caller's uncommitted draft and would hold a second connection
            throw new IllegalStateException("Issue a fiscal document outside any transaction: commit the draft "
                    + "first (D50 §7.6 tx-A), then call issue");
        }
        UUID terminalId = accessPolicy.current().terminalId();
        try {
            return inNewTransaction.execute(status -> issueNow(documentId, actorEmployeeId, terminalId));
        } catch (DocumentNotFoundException e) {
            throw e; // nothing to keep pending
        } catch (RuntimeException e) {
            log.warn("Fiscal document {} could not be issued; it stays a pending DRAFT", documentId, e);
            recordFailure(documentId, e);
            throw new FiscalIssueFailedException(documentId, e);
        }
    }

    private DocumentView issueNow(UUID documentId, UUID employeeId, UUID terminalId) {
        FiscalDocument document = documents.lockById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (document.isIssued()) {
            return document.toView();
        }
        FiscalSeries numbering = series.lockActive(document.getStoreId(), document.getDocumentType())
                .orElseThrow(() -> new NoActiveSeriesException(document.getStoreId()));
        // PostgreSQL keeps microseconds: the hashed and returned time must be the stored one (D54 chain re-derivable)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long number = numbering.takeNextNumber(now);
        IssueResult result = adapter.issue(new IssueRequest(document.getId(), document.getDocumentType().name(),
                numbering.getCode(), number, now, document.getTotalGross(), document.getTotalTax(),
                previousAdapterData(numbering, number), document.isOnAccount(), null));
        document.issue(numbering, number, now, employeeId, terminalId, adapter.code(), !adapter.certified(), result);
        documents.flush();
        events.publishEvent(new FiscalDocumentIssued(document.getId(), document.getStoreId(), terminalId,
                adapter.printout(document.toIssuedDocument(), false)));
        return document.toView();
    }

    /**
     * What the BackOffice may see: a business rule's own message, otherwise only the failure type (a technical
     * message can carry SQL or constraint names). The full trace is in the WARN log line above.
     */
    static String reasonFor(RuntimeException failure) {
        if (failure instanceof BusinessRuleException || failure instanceof ConflictException
                || failure instanceof NotFoundException) {
            return failure.getMessage();
        }
        return "Technical failure (" + failure.getClass().getSimpleName() + "); see the server log";
    }

    private String previousAdapterData(FiscalSeries numbering, long number) {
        if (number == numbering.getFirstNumber()) {
            return null;
        }
        return documents.findBySeriesIdAndNumber(numbering.getId(), number - 1)
                .map(FiscalDocument::getAdapterData).orElse(null);
    }

    private void recordFailure(UUID documentId, RuntimeException failure) {
        String reason = reasonFor(failure);
        try {
            inNewTransaction.executeWithoutResult(status -> documents.findById(documentId)
                    .filter(document -> !document.isIssued())
                    .ifPresent(document -> document.recordIssueFailure(reason, clock.instant())));
        } catch (RuntimeException recordingFailed) {
            failure.addSuppressed(recordingFailed);
        }
    }
}
