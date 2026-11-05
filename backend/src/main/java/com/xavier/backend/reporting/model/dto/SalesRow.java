package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Sales of one group (PRD S32): the documents it appears on, the quantity sold, and the gross, net and tax of its
 * document lines (the document's own amounts, D84).
 */
public record SalesRow(String key, String label, long documents, BigDecimal quantity, Money gross, Money net,
                       Money tax) {
}
