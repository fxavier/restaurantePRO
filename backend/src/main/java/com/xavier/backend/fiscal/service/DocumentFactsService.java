package com.xavier.backend.fiscal.service;

import com.xavier.backend.fiscal.exception.DocumentNotFoundException;
import com.xavier.backend.fiscal.exception.UnknownStoreException;
import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.Coverage.CoveredLine;
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only document facts for other modules (D66, D70, D84): an order's documents and coverage, one document's facts
 * and the backfill listing. Not authorized here: the calling use case already did (D27, D36).
 */
@Service
@Transactional(readOnly = true)
public class DocumentFactsService {

    private final FiscalDocumentRepository documents;
    private final OrganizationApi organization;

    DocumentFactsService(FiscalDocumentRepository documents, OrganizationApi organization) {
        this.documents = documents;
        this.organization = organization;
    }

    public DocumentFacts documentFacts(UUID documentId) {
        FiscalDocument document = documents.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return document.toFacts(() -> store(document.getStoreId()));
    }

    public Page<UUID> issuedDocumentIds(UUID storeId, LocalDate from, LocalDate to, Pageable page) {
        StoreSummary store = store(storeId); // R2 rows: the store's current zone and cutoff, as documentFacts (D85)
        Instant start = startOfBusinessDay(from, store.timezone(), store.businessDayCutoff());
        Instant end = startOfBusinessDay(to.plusDays(1), store.timezone(), store.businessDayCutoff());
        return documents.findIssuedIds(storeId, from, to, start, end, page);
    }

    /**
     * The first instant of business day {@code day}: the inverse of {@link BusinessDays#of}, with its DST rule (a
     * cutoff in a gap starts the day at the jump; in an overlap, the earlier offset). Only R2 documents, which have
     * no business date, are matched by it.
     */
    static Instant startOfBusinessDay(LocalDate day, ZoneId zone, LocalTime cutoff) {
        LocalDateTime start = day.atTime(cutoff);
        ZoneOffsetTransition gap = zone.getRules().getTransition(start);
        if (gap != null && gap.isGap()) {
            return gap.getInstant();
        }
        return ZonedDateTime.of(start, zone).toInstant();
    }

    private StoreSummary store(UUID storeId) {
        return organization.findStore(storeId).orElseThrow(() -> new UnknownStoreException(storeId));
    }

    public List<DocumentView> documentsOfOrder(UUID orderId) {
        return documents.findByOrder(orderId).stream().map(FiscalDocument::toView).toList();
    }

    public Coverage coverage(UUID orderId) {
        Map<UUID, CoveredLine> issued = new LinkedHashMap<>();
        Map<UUID, CoveredLine> draft = new LinkedHashMap<>();
        List<UUID> draftIds = new ArrayList<>();
        for (FiscalDocument document : documents.findByOrder(orderId)) {
            Map<UUID, CoveredLine> into = document.isIssued() ? issued : draft;
            if (!document.isIssued()) {
                draftIds.add(document.getId());
            }
            for (DocumentView.Line line : document.toView().lines()) {
                into.merge(line.sourceLineId(),
                        new CoveredLine(line.sourceLineId(), line.quantity(), line.gross(), line.tax()),
                        DocumentFactsService::sum);
            }
        }
        return new Coverage(List.copyOf(issued.values()), List.copyOf(draft.values()), List.copyOf(draftIds));
    }

    private static CoveredLine sum(CoveredLine a, CoveredLine b) {
        return new CoveredLine(a.sourceLineId(), a.quantity().add(b.quantity()), a.gross().plus(b.gross()),
                a.tax().plus(b.tax()));
    }
}
