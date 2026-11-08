package com.xavier.backend.reporting.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * The margin of one group (D131): {@code netSales} from the issued documents' lines; {@code costOfSales} the cost of
 * the consumption of what was sold, less its reversals and less what wasted voids moved to waste; {@code waste} the
 * cost of wasted voids and manual waste, apart; {@code costVariance} cost of goods corrected after it was consumed
 * (positive is more cost). {@code margin} = net sales − cost of sales − cost variance; {@code marginPercent} is its
 * share of net sales (scale 2), {@code null} without net sales.
 */
public record MarginRow(String key, String label, Money netSales, Money costOfSales, Money waste, Money costVariance,
                        Money margin, BigDecimal marginPercent) {
}
