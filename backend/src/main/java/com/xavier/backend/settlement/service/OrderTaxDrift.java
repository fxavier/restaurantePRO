package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.model.dto.TaxRoundingDifference;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * An order's tax drift per rate and where it went (D96 rule 7), exactly as
 * {@code OrderSettlementView.taxRoundingDifference} shows it, and its adjustment rows. For settlement's own controls
 * (the documents control, D80); it does not authorize: the calling use case did.
 */
@Component
public class OrderTaxDrift {

    private final Bills bills;
    private final TaxRoundingAdjustments taxRounding;

    OrderTaxDrift(Bills bills, TaxRoundingAdjustments taxRounding) {
        this.bills = bills;
        this.taxRounding = taxRounding;
    }

    @Transactional(readOnly = true)
    public List<TaxRoundingDifference> of(UUID orderId) {
        return taxRounding.differences(bills.of(orderId));
    }

    /** The order's tax rounding adjustments with their resolutions, oldest first. */
    @Transactional(readOnly = true)
    public List<com.xavier.backend.settlement.model.dto.TaxRoundingAdjustmentView> adjustments(UUID orderId) {
        return taxRounding.views(orderId);
    }
}
