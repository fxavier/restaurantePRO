package com.xavier.backend.orders.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.model.CatalogPricing;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.LineStatus;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.dto.BatchView;
import com.xavier.backend.orders.model.dto.LineView;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reads orders into views. The net quantity of a confirmed line is its quantity minus the void and transfer facts,
 * read with it and never stored; amounts come from {@link LineAmounts} only (D59).
 */
@Component
public class OrderViews {

    private final OrderLineRepository lines;
    private final OrderBatchRepository batches;
    private final CatalogApi catalog;

    OrderViews(OrderLineRepository lines, OrderBatchRepository batches, CatalogApi catalog) {
        this.lines = lines;
        this.batches = batches;
        this.catalog = catalog;
    }

    public OrderView of(Order order) {
        List<OrderLine> all = lines.findByOrderIdOrderById(order.getId());
        Map<UUID, BigDecimal> deducted = deductions(List.of(order.getId()));
        Optional<CatalogSnapshot> current = all.stream().anyMatch(OrderLine::isDraft)
                ? Optional.of(catalog.currentSnapshot(order.getStoreId())) : Optional.empty();
        List<LineView> lineViews = new ArrayList<>();
        all.stream().filter(OrderLine::isDraft).forEach(line -> lineViews.add(draftView(line, current.orElseThrow())));
        all.stream().filter(line -> !line.isDraft())
                .forEach(line -> lineViews.add(confirmedView(line, deducted.getOrDefault(line.getId(), BigDecimal.ZERO))));
        List<BatchView> batchViews = batches.findByOrderIdOrderByBatchNo(order.getId()).stream()
                .map(batch -> batchView(batch, order.getVersion(), lineViews)).toList();
        return new OrderView(order.getId(), order.getStoreId(), order.getTerminalId(), order.getOrderNumber(),
                order.getMode(), order.getTableId(), order.getTableCode(), order.getGuests(), order.getLifecycle(),
                order.getBillingLock(), order.getBusinessDay(), order.getOpenedAt(), order.getOpenedByEmployeeId(),
                order.getVersion(), current.map(CatalogSnapshot::version).orElse(null), lineViews, batchViews,
                due(order, billingLines(all, deducted)));
    }

    public LineView draftView(OrderLine line, CatalogSnapshot current) {
        Optional<Money> price = CatalogPricing.previewPrice(current, line.getItemId(), line.getVariantId());
        return new LineView(line.getId(), LineStatus.DRAFT, line.getItemId(), line.getVariantId(), line.getQuantity(),
                null, line.getNotes(), line.getVersion(), null, null, null, null, price.orElse(null), null,
                price.map(p -> p.times(line.getQuantity())).orElse(null), true);
    }

    public BatchView batchView(OrderBatch batch, long orderVersion, List<LineView> lineViews) {
        return new BatchView(batch.getId(), batch.getOrderId(), batch.getBatchNo(), batch.getStatus(),
                batch.getConfirmedAt(), batch.getConfirmedByEmployeeId(), orderVersion,
                lineViews.stream().filter(view -> batch.getId().equals(view.batchId())).toList());
    }

    public OrderBillingView billing(Order order) {
        return billing(List.of(order)).getFirst();
    }

    /** One read of lines and deductions for all {@code orders}, in their order. */
    public List<OrderBillingView> billing(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = orders.stream().map(Order::getId).toList();
        Map<UUID, List<OrderLine>> confirmed = lines.findByOrderIdInAndStatusOrderById(ids, LineStatus.CONFIRMED)
                .stream().collect(Collectors.groupingBy(OrderLine::getOrderId));
        Map<UUID, BigDecimal> deducted = deductions(ids);
        return orders.stream().map(order -> {
            List<BillingLine> billable = billingLines(confirmed.getOrDefault(order.getId(), List.of()), deducted);
            return new OrderBillingView(order.getId(), order.getStoreId(), order.getVersion(), order.getLifecycle(),
                    order.getBillingLock(), order.getBusinessDay(), billable, due(order, billable));
        }).toList();
    }

    private LineView confirmedView(OrderLine line, BigDecimal deducted) {
        LineSnapshot snapshot = line.snapshot().orElseThrow();
        BigDecimal net = line.getQuantity().subtract(deducted);
        return new LineView(line.getId(), LineStatus.CONFIRMED, line.getItemId(), line.getVariantId(),
                line.getQuantity(), net, line.getNotes(), line.getVersion(), line.getBatchId(), snapshot.itemCode(),
                snapshot.description(), snapshot.variantName(), snapshot.unitPrice(), snapshot.taxRatePercent(),
                LineAmounts.of(snapshot.unitPrice(), net, snapshot.taxRatePercent()).gross(), false);
    }

    private static List<BillingLine> billingLines(Collection<OrderLine> candidates, Map<UUID, BigDecimal> deducted) {
        return candidates.stream().filter(line -> !line.isDraft()).map(line -> {
            LineSnapshot snapshot = line.snapshot().orElseThrow();
            BigDecimal net = line.getQuantity().subtract(deducted.getOrDefault(line.getId(), BigDecimal.ZERO));
            LineAmounts amounts = LineAmounts.of(snapshot.unitPrice(), net, snapshot.taxRatePercent());
            return new BillingLine(line.getId(), snapshot.billedDescription(), net, snapshot.unitPrice(), amounts.gross(),
                    amounts.net(), amounts.tax(), snapshot.taxCode(), snapshot.taxRatePercent());
        }).filter(line -> line.netQty().signum() > 0).toList();
    }

    private static Money due(Order order, List<BillingLine> billable) {
        return billable.stream().map(BillingLine::gross)
                .reduce(Money.of(BigDecimal.ZERO, order.currency()), Money::plus);
    }

    /** Σ voids + Σ transferred out of one confirmed line, as read now. */
    public BigDecimal deducted(OrderLine line) {
        return deductions(List.of(line.getOrderId())).getOrDefault(line.getId(), BigDecimal.ZERO);
    }

    /** Σ voids + Σ transferred out of each confirmed line of the orders, as read now. */
    public Map<UUID, BigDecimal> deductions(List<UUID> orderIds) {
        return lines.deductions(orderIds).stream().collect(Collectors.toMap(
                OrderLineRepository.LineDeduction::getLineId, OrderLineRepository.LineDeduction::getDeducted));
    }
}
