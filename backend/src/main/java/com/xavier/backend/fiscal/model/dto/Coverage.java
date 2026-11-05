package com.xavier.backend.fiscal.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * What an order's documents already cover (D70 step 1), per source (order) line: {@code issued} sums its ISSUED
 * documents and {@code draft} its DRAFT ones, whatever their source (a partition, the residual document, or an R2
 * document). {@code draftDocumentIds} are the DRAFT documents still to issue.
 */
public record Coverage(List<CoveredLine> issued, List<CoveredLine> draft, List<UUID> draftDocumentIds) {

    /** Σ quantity, gross and tax of one source line over a set of documents. */
    public record CoveredLine(UUID sourceLineId, BigDecimal quantity, Money gross, Money tax) {
    }
}
