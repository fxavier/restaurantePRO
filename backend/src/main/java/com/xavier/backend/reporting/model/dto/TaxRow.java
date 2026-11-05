package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Net and tax per tax code and rate, summed from the issued documents' tax summaries (never recomputed, D68
 * amended). {@code taxCode} is empty when no line of the document said which code it was.
 */
public record TaxRow(String taxCode, BigDecimal rate, Money net, Money tax) {
}
