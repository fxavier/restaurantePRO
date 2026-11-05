package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Voids of one group: how many, the quantity and gross voided, how many of them were wasted, and how many came after
 * production had received the round (PRD S32).
 */
public record VoidsRow(String key, String label, long voids, BigDecimal quantity, Money gross, long wasted,
                       long afterDispatch) {
}
