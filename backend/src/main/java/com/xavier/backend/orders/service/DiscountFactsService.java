package com.xavier.backend.orders.service;

import com.xavier.backend.orders.DiscountFacts;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.SourceRef;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import com.xavier.backend.orders.model.PromotionRef;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLinePriceAdjustmentRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Reads discount facts for reporting (D100) through {@code OrdersApi}, in the caller's read-only transaction. Rounds,
 * lines and adjustments are immutable, so each pull returns the same answer; nothing here authorizes (D27).
 */
@Service
public class DiscountFactsService {

    private final OrderBatchRepository batches;
    private final OrderLineRepository lines;
    private final OrderLinePriceAdjustmentRepository adjustments;
    private final OrderLookup lookup;

    DiscountFactsService(OrderBatchRepository batches, OrderLineRepository lines,
                         OrderLinePriceAdjustmentRepository adjustments, OrderLookup lookup) {
        this.batches = batches;
        this.lines = lines;
        this.adjustments = adjustments;
        this.lookup = lookup;
    }

    public DiscountFacts facts(String sourceType, UUID sourceId) {
        return switch (sourceType) {
            case LineDiscountsRecorded.BATCH -> batch(sourceId);
            case LineDiscountsRecorded.ADJUSTMENT -> adjustment(sourceId);
            default -> throw new OrderNotFoundException("DiscountSource", sourceId);
        };
    }

    public Page<SourceRef> sourceIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return adjustments.findDiscountSources(storeId, from, to,
                        PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()))
                .map(source -> new SourceRef(source.getSourceType(), source.getSourceId()));
    }

    private DiscountFacts batch(UUID batchId) {
        OrderBatch batch = batches.findById(batchId)
                .orElseThrow(() -> new OrderNotFoundException("DiscountSource", batchId));
        Order order = lookup.load(batch.getOrderId());
        List<DiscountFacts.DiscountLine> promoted = lines.findByBatchIdOrderById(batchId).stream()
                .filter(line -> line.promotion().isPresent())
                .map(line -> {
                    LineSnapshot snapshot = line.snapshot().orElseThrow();
                    PromotionRef promotion = line.promotion().orElseThrow();
                    return line(line, snapshot, line.listUnitPrice(), snapshot.unitPrice(), line.getQuantity(),
                            "PROMOTION", promotion, line.getConfirmedByEmployeeId(), null, line.getConfirmedAt());
                }).toList();
        return new DiscountFacts(LineDiscountsRecorded.BATCH, batchId, order.getId(), order.getStoreId(),
                order.getBusinessDay(), promoted);
    }

    private DiscountFacts adjustment(UUID adjustmentId) {
        OrderLinePriceAdjustment adjustment = adjustments.findById(adjustmentId)
                .orElseThrow(() -> new OrderNotFoundException("DiscountSource", adjustmentId));
        Order order = lookup.load(adjustment.getOrderId());
        OrderLine line = lines.findById(adjustment.getLineId()).orElseThrow();
        String origin = switch (adjustment.getKind()) {
            case OFFER -> "OFFER";
            case REWARD -> "LOYALTY";
            case DISCOUNT, REVERSAL -> "MANUAL";
        };
        return new DiscountFacts(LineDiscountsRecorded.ADJUSTMENT, adjustmentId, order.getId(), order.getStoreId(),
                order.getBusinessDay(), List.of(line(line, line.snapshot().orElseThrow(),
                adjustment.previousUnitPrice(), adjustment.unitPrice(), adjustment.getQuantity(), origin, null,
                adjustment.getOperatorEmployeeId(), adjustment.getApproverEmployeeId(), adjustment.getAt())));
    }

    private static DiscountFacts.DiscountLine line(OrderLine line, LineSnapshot snapshot, Money previous, Money price,
                                                   BigDecimal quantity, String origin, PromotionRef promotion,
                                                   UUID operator, UUID approver, java.time.Instant at) {
        Money discount = LineAmounts.of(previous, quantity, snapshot.taxRatePercent()).gross()
                .minus(LineAmounts.of(price, quantity, snapshot.taxRatePercent()).gross());
        return new DiscountFacts.DiscountLine(line.getId(), line.getItemId(), snapshot.itemCode(),
                snapshot.releaseId(), snapshot.taxCode(), snapshot.taxRatePercent(), line.listUnitPrice(), previous,
                price, quantity, discount, origin, promotion == null ? null : promotion.id(),
                promotion == null ? null : promotion.code(), promotion == null ? null : promotion.name(), operator,
                approver, at);
    }
}
