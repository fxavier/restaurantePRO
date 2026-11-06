package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.model.dto.TaxRoundingDifference;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An order's tax drift per rate (D68 amendment): its lines' recomputed tax minus what its split gives its documents,
 * exactly as {@code OrderSettlementView.taxRoundingDifference} shows it. For settlement's own controls (the
 * documents control, D80); it does not authorize: the calling use case did.
 */
@Component
public class OrderTaxDrift {

    private final Bills bills;

    OrderTaxDrift(Bills bills) {
        this.bills = bills;
    }

    @Transactional(readOnly = true)
    public List<TaxRoundingDifference> of(UUID orderId) {
        return SettlementViews.taxRoundingDifferences(bills.of(orderId));
    }
}
