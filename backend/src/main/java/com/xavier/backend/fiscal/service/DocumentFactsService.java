package com.xavier.backend.fiscal.service;

import com.xavier.backend.fiscal.model.FiscalDocument;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.Coverage.CoveredLine;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.repository.FiscalDocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only document facts for other modules (D66, D70, D84): an order's documents and their coverage. Not
 * authorized here: the calling use case already did (D27, D36).
 */
@Service
@Transactional(readOnly = true)
public class DocumentFactsService {

    private final FiscalDocumentRepository documents;

    DocumentFactsService(FiscalDocumentRepository documents) {
        this.documents = documents;
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
