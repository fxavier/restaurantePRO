package com.xavier.backend.orders.model.event;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import org.springframework.modulith.NamedInterface;

/** One billable line of a subtotal printout: net quantity and its gross (D59). */
@NamedInterface("event")
public record SubtotalLine(String description, BigDecimal netQty, Money unitPrice, Money gross) {
}
