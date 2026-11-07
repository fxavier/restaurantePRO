package com.xavier.backend.orders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What a CLOSED order gave away as offers (S53, for reporting's cost of offers): its billing lines whose effective
 * price comes from an OFFER, through their lineage (a split or transferred copy of an offered line is one), at a unit
 * price of 0 (an offer is a free unit; one reversed part way to a positive price is a discounted line). Empty while
 * the order is open. {@code businessDay} is the order's.
 */
public record OfferFacts(UUID orderId, UUID storeId, LocalDate businessDay, List<OfferLine> lines) {

    /**
     * One offered billing line: {@code netQuantity} is what is still billed (voids, transfers out and splits out left
     * out), {@code consumedLineId} the round line it descends from, whose consumption carries its cost, and
     * {@code consumedLineQuantity} that line's confirmed quantity. The employees are the offer's operator and
     * approver; {@code promotionCode} the line's promotion, when it had one.
     */
    public record OfferLine(UUID lineId, UUID itemId, UUID releaseId, UUID consumedLineId,
                            BigDecimal consumedLineQuantity, BigDecimal netQuantity, UUID operatorEmployeeId,
                            UUID approverEmployeeId, String promotionCode) {
    }
}
