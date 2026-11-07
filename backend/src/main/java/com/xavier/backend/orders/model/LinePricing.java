package com.xavier.backend.orders.model;

import com.xavier.backend.shared.Money;
import java.util.Objects;

/**
 * What a line is priced at when it is confirmed (D101–D103): the catalog's {@code listUnitPrice}, the effective
 * {@code unitPrice} ({@code 0 ≤ unitPrice ≤ listUnitPrice}, same currency) and the promotion that set it, if any.
 */
public record LinePricing(Money listUnitPrice, Money unitPrice, PromotionRef promotion) {

    public LinePricing {
        Objects.requireNonNull(listUnitPrice, "listUnitPrice");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (!listUnitPrice.currency().equals(unitPrice.currency())) {
            throw new IllegalArgumentException("List and effective price in different currencies");
        }
        if (unitPrice.amount().signum() < 0 || unitPrice.amount().compareTo(listUnitPrice.amount()) > 0) {
            throw new IllegalArgumentException("An effective price is within [0, list]: " + unitPrice.amount()
                    + " of " + listUnitPrice.amount());
        }
    }

    /** No promotion: the line sells at its list price. */
    public static LinePricing atList(Money listUnitPrice) {
        return new LinePricing(listUnitPrice, listUnitPrice, null);
    }
}
