package com.xavier.backend.orders.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.DeliverySummary;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.delivery.model.Delivery;
import com.xavier.backend.orders.delivery.repository.DeliveryRepository;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.OfferFacts;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.model.EffectivePrice;
import com.xavier.backend.orders.model.LinePricing;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.LineStatus;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderBatch;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderLineComponent;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import com.xavier.backend.orders.model.PromotionRef;
import com.xavier.backend.orders.model.dto.BatchView;
import com.xavier.backend.orders.model.dto.LineView;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderBatchRepository;
import com.xavier.backend.orders.repository.OrderLineComponentRepository;
import com.xavier.backend.orders.repository.OrderLinePriceAdjustmentRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Reads orders into views. The net quantity of a confirmed line is its quantity minus the void, transfer and split
 * facts, read with it and never stored; its effective price is its latest adjustment's, else its snapshot's (D105);
 * amounts come from {@link LineAmounts} only (D59, D101). Drafts are previewed as confirmation would price them now.
 */
@Component
public class OrderViews {

    private final OrderLineRepository lines;
    private final OrderBatchRepository batches;
    private final OrderLineComponentRepository components;
    private final OrderLinePriceAdjustmentRepository adjustments;
    private final CatalogApi catalog;
    private final LinePricer pricer;
    private final RewardLineage rewards;
    private final DeliveryRepository deliveryRepository;
    private final Clock clock;

    OrderViews(OrderLineRepository lines, OrderBatchRepository batches, OrderLineComponentRepository components,
               OrderLinePriceAdjustmentRepository adjustments, CatalogApi catalog, LinePricer pricer,
               RewardLineage rewards, DeliveryRepository deliveryRepository, Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.rewards = rewards;
        this.lines = lines;
        this.batches = batches;
        this.components = components;
        this.adjustments = adjustments;
        this.catalog = catalog;
        this.pricer = pricer;
        this.clock = clock;
    }

    public OrderView of(Order order) {
        List<OrderLine> all = lines.findByOrderIdOrderById(order.getId());
        Map<UUID, BigDecimal> deducted = deductions(List.of(order.getId()));
        Map<UUID, List<OrderLinePriceAdjustment>> adjusted = adjustments(List.of(order.getId()));
        Map<UUID, List<OrderLineComponent>> chosen = components.findByOrderIdOrderByLineIdAscPositionAsc(order.getId())
                .stream().collect(Collectors.groupingBy(OrderLineComponent::getLineId));
        List<OrderLine> drafts = all.stream().filter(OrderLine::isDraft).toList();
        Optional<CatalogSnapshot> current = drafts.isEmpty() ? Optional.empty()
                : Optional.of(catalog.currentSnapshot(order.getStoreId()));
        LinePricer.Priced priced = current.map(snapshot -> pricer.price(order, snapshot, drafts, clock.instant()))
                .orElse(null);
        List<LineView> lineViews = new ArrayList<>();
        drafts.forEach(line -> lineViews.add(draftView(line, priced.of(line.getId()),
                chosen.getOrDefault(line.getId(), List.of()))));
        Map<UUID, String> inherited = inheritedRewards(all, adjusted);
        Map<UUID, OrderLinePriceAdjustment> lineage = lineageOrigins(all, adjusted);
        all.stream().filter(line -> !line.isDraft()).forEach(line -> lineViews.add(confirmedView(line,
                deducted.getOrDefault(line.getId(), BigDecimal.ZERO), adjusted.getOrDefault(line.getId(), List.of()),
                inherited.get(line.getId()), lineage.get(line.getId()),
                chosen.getOrDefault(line.getId(), List.of()))));
        List<BatchView> batchViews = batches.findByOrderIdOrderByBatchNo(order.getId()).stream()
                .map(batch -> batchView(batch, order.getVersion(), lineViews)).toList();
        Long promotionVersion = priced == null ? pricer.currentVersion(order.getStoreId()) : priced.promotionVersion();
        return new OrderView(order.getId(), order.getStoreId(), order.getTerminalId(), order.getOrderNumber(),
                order.getMode(), order.getTableId(), order.getTableCode(), order.getGuests(), order.getLifecycle(),
                order.getBillingLock(), order.getBusinessDay(), order.getOpenedAt(), order.getOpenedByEmployeeId(),
                order.getVersion(), current.map(CatalogSnapshot::version).orElse(null), lineViews, batchViews,
                due(order, billingLines(all, deducted, adjusted, inherited, lineage)), promotionVersion,
                order.getCustomerId());
    }

    /** One draft as the POS shows it right after a change: priced alone, as confirmation would price it now. */
    public LineView draftView(Order order, OrderLine line, CatalogSnapshot current) {
        LinePricer.Priced priced = pricer.price(order, current, List.of(line), clock.instant());
        return draftView(line, priced.of(line.getId()), components.findByLineIdOrderByPosition(line.getId()));
    }

    private static LineView draftView(OrderLine line, Optional<LinePricing> pricing,
                                      List<OrderLineComponent> chosen) {
        Money unit = pricing.map(LinePricing::unitPrice).orElse(null);
        Money list = pricing.map(LinePricing::listUnitPrice).orElse(null);
        Optional<PromotionRef> promotion = pricing.map(LinePricing::promotion);
        Money discount = pricing.map(p -> p.listUnitPrice().times(line.getQuantity())
                .minus(p.unitPrice().times(line.getQuantity()))).orElse(null);
        return new LineView(line.getId(), LineStatus.DRAFT, line.getItemId(), line.getVariantId(), line.getQuantity(),
                null, line.getNotes(), line.getVersion(), null, null, null, null, unit, null,
                pricing.map(p -> p.unitPrice().times(line.getQuantity())).orElse(null), true, null,
                line.isAutomatic(), list, discount, promotion.map(p -> "PROMOTION").orElse(null),
                promotion.map(PromotionRef::name).orElse(null), promotion.map(PromotionRef::code).orElse(null),
                promotion.map(PromotionRef::name).orElse(null), componentViews(chosen), List.of());
    }

    public BatchView batchView(OrderBatch batch, long orderVersion, List<LineView> lineViews) {
        return new BatchView(batch.getId(), batch.getOrderId(), batch.getBatchNo(), batch.getStatus(),
                batch.getConfirmedAt(), batch.getConfirmedByEmployeeId(), orderVersion,
                lineViews.stream().filter(view -> batch.getId().equals(view.batchId())).toList());
    }

    public OrderBillingView billing(Order order) {
        return billing(List.of(order)).getFirst();
    }

    /** One read of lines, deductions and adjustments for all {@code orders}, in their order. */
    public List<OrderBillingView> billing(List<Order> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = orders.stream().map(Order::getId).toList();
        Map<UUID, List<OrderLine>> confirmed = lines.findByOrderIdInAndStatusOrderById(ids, LineStatus.CONFIRMED)
                .stream().collect(Collectors.groupingBy(OrderLine::getOrderId));
        Map<UUID, BigDecimal> deducted = deductions(ids);
        Map<UUID, List<OrderLinePriceAdjustment>> adjusted = adjustments(ids);
        List<OrderLine> all = confirmed.values().stream().flatMap(List::stream).toList();
        Map<UUID, String> inherited = inheritedRewards(all, adjusted);
        Map<UUID, OrderLinePriceAdjustment> lineage = lineageOrigins(all, adjusted);
        Map<UUID, DeliverySummary> delivery = deliveries(orders);
        return orders.stream().map(order -> {
            List<BillingLine> billable = billingLines(confirmed.getOrDefault(order.getId(), List.of()), deducted,
                    adjusted, inherited, lineage);
            return new OrderBillingView(order.getId(), order.getStoreId(), order.getVersion(), order.getLifecycle(),
                    order.getBillingLock(), order.getBusinessDay(), billable, due(order, billable), order.getMode(),
                    order.getTableId(), order.getTableCode(), order.getCustomerId(), delivery.get(order.getId()));
        }).toList();
    }

    /** The delivery summaries of the DELIVERY orders among {@code orders} (D129). */
    private Map<UUID, DeliverySummary> deliveries(List<Order> orders) {
        List<UUID> ids = orders.stream().filter(order -> order.getMode() == OrderMode.DELIVERY).map(Order::getId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return deliveryRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Delivery::getOrderId, Delivery::summary));
    }

    private static LineView confirmedView(OrderLine line, BigDecimal deducted,
                                          List<OrderLinePriceAdjustment> adjusted, String inheritedReward,
                                          OrderLinePriceAdjustment lineageOrigin, List<OrderLineComponent> chosen) {
        LineSnapshot snapshot = line.snapshot().orElseThrow();
        EffectivePrice price = EffectivePrice.of(line, adjusted, inheritedReward, lineageOrigin);
        BigDecimal net = line.getQuantity().subtract(deducted);
        Money gross = LineAmounts.of(price.unitPrice(), net, snapshot.taxRatePercent()).gross();
        Money discount = LineAmounts.of(price.listUnitPrice(), net, snapshot.taxRatePercent()).gross().minus(gross);
        Optional<PromotionRef> promotion = line.promotion();
        return new LineView(line.getId(), LineStatus.CONFIRMED, line.getItemId(), line.getVariantId(),
                line.getQuantity(), net, line.getNotes(), line.getVersion(), line.getBatchId(), snapshot.itemCode(),
                snapshot.description(), snapshot.variantName(), price.unitPrice(), snapshot.taxRatePercent(), gross,
                false, line.getKind(), line.isAutomatic(), price.listUnitPrice(), discount, price.origin(),
                price.label(), promotion.map(PromotionRef::code).orElse(null),
                promotion.map(PromotionRef::name).orElse(null), componentViews(chosen),
                adjusted.stream().map(OrderViews::adjustmentView).toList());
    }

    private static List<LineView.ComponentView> componentViews(List<OrderLineComponent> chosen) {
        return chosen.stream().map(component -> new LineView.ComponentView(component.getId(),
                component.getGroupCode(), component.getItemId(), component.getVariantId(), component.getItemCode(),
                component.getDescription(), component.getQuantity(), component.getDestination())).toList();
    }

    private static LineView.AdjustmentView adjustmentView(OrderLinePriceAdjustment adjustment) {
        return new LineView.AdjustmentView(adjustment.getId(), adjustment.getSeq(), adjustment.getKind().name(),
                adjustment.getQuantity(), adjustment.getPercent(), adjustment.previousUnitPrice(),
                adjustment.unitPrice(), adjustment.getRewardCode(), adjustment.getReason(),
                adjustment.getOperatorEmployeeId(), adjustment.getApproverEmployeeId(), adjustment.getAt());
    }

    private static List<BillingLine> billingLines(Collection<OrderLine> candidates, Map<UUID, BigDecimal> deducted,
                                                  Map<UUID, List<OrderLinePriceAdjustment>> adjusted,
                                                  Map<UUID, String> inherited,
                                                  Map<UUID, OrderLinePriceAdjustment> lineage) {
        return candidates.stream().filter(line -> !line.isDraft()).map(line -> {
            LineSnapshot snapshot = line.snapshot().orElseThrow();
            EffectivePrice price = EffectivePrice.of(line, adjusted.getOrDefault(line.getId(), List.of()),
                    inherited.get(line.getId()), lineage.get(line.getId()));
            BigDecimal net = line.getQuantity().subtract(deducted.getOrDefault(line.getId(), BigDecimal.ZERO));
            LineAmounts amounts = LineAmounts.of(price.unitPrice(), net, snapshot.taxRatePercent());
            return new BillingLine(line.getId(), snapshot.billedDescription(), net, price.unitPrice(),
                    amounts.gross(), amounts.net(), amounts.tax(), snapshot.taxCode(), snapshot.taxRatePercent(),
                    line.getItemId(), snapshot.itemCode(), snapshot.releaseId(), line.getConfirmedByEmployeeId(),
                    line.getConfirmedAt(), price.listUnitPrice(), price.label());
        }).filter(line -> line.netQty().signum() > 0).toList();
    }

    private static Money due(Order order, List<BillingLine> billable) {
        return billable.stream().map(BillingLine::gross)
                .reduce(Money.of(BigDecimal.ZERO, order.currency()), Money::plus);
    }

    /** The line's price now: its latest adjustment, else its snapshot (D105). */
    public EffectivePrice effectivePrice(OrderLine line) {
        return EffectivePrice.of(line, adjustments.findByLineIdOrderBySeq(line.getId()));
    }

    /** Σ voids + Σ transferred out + Σ split out of one confirmed line, as read now. */
    public BigDecimal deducted(OrderLine line) {
        return deductions(List.of(line.getOrderId())).getOrDefault(line.getId(), BigDecimal.ZERO);
    }

    /** Σ voids + Σ transferred out + Σ split out of each confirmed line of the orders, as read now. */
    public Map<UUID, BigDecimal> deductions(List<UUID> orderIds) {
        return lines.deductions(orderIds).stream().collect(Collectors.toMap(
                OrderLineRepository.LineDeduction::getLineId, OrderLineRepository.LineDeduction::getDeducted));
    }

    /**
     * D112a: the reward code of each copy of a rewarded line, for its label. Only lines that sell below list with no
     * adjustment and no promotion of their own can carry one, so the lineage is read for those alone.
     */
    private Map<UUID, String> inheritedRewards(Collection<OrderLine> candidates,
                                               Map<UUID, List<OrderLinePriceAdjustment>> adjusted) {
        List<UUID> copies = candidates.stream().filter(line -> !line.isDraft()
                        && (line.getOriginTransferId() != null || line.getOriginSplitId() != null)
                        && !adjusted.containsKey(line.getId()) && line.promotion().isEmpty()
                        && !line.snapshot().orElseThrow().unitPrice().equals(line.listUnitPrice()))
                .map(OrderLine::getId).toList();
        return rewards.rewardCodes(copies);
    }

    /** S53: the lineage price origins of the copies (split or transfer) without an adjustment of their own. */
    private Map<UUID, OrderLinePriceAdjustment> lineageOrigins(Collection<OrderLine> candidates,
                                                               Map<UUID, List<OrderLinePriceAdjustment>> adjusted) {
        List<UUID> ids = candidates.stream().filter(line -> !line.isDraft())
                .filter(line -> line.getOriginTransferId() != null || line.getOriginSplitId() != null)
                .filter(line -> !adjusted.containsKey(line.getId())).map(OrderLine::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<OrderLinePriceAdjustmentRepository.PriceOrigin> origins = adjustments.findPriceOrigins(ids, null);
        Map<UUID, OrderLinePriceAdjustment> byId = adjustments.findAllById(origins.stream()
                        .map(OrderLinePriceAdjustmentRepository.PriceOrigin::getAdjustmentId).toList()).stream()
                .collect(Collectors.toMap(OrderLinePriceAdjustment::getId, Function.identity()));
        return origins.stream().collect(Collectors.toMap(OrderLinePriceAdjustmentRepository.PriceOrigin::getLineId,
                origin -> byId.get(origin.getAdjustmentId())));
    }

    /**
     * S53: the order's billing lines given away as an OFFER (their effective price origin, through their lineage, and a
     * unit price of 0: an offer is a free unit), with the
     * offer that made them so and the round line whose consumption carries their cost. The net quantity already
     * leaves out voids, transfers out and splits out, and a reversed line is no longer an offer.
     */
    public List<OfferFacts.OfferLine> offers(Order order) {
        List<OrderLine> confirmed = lines.findByOrderIdAndStatusOrderById(order.getId(), LineStatus.CONFIRMED);
        Map<UUID, BigDecimal> deducted = deductions(List.of(order.getId()));
        Map<UUID, List<OrderLinePriceAdjustment>> adjusted = adjustments(List.of(order.getId()));
        Map<UUID, String> inherited = inheritedRewards(confirmed, adjusted);
        Map<UUID, OrderLinePriceAdjustment> lineage = lineageOrigins(confirmed, adjusted);
        List<OfferFacts.OfferLine> offers = new ArrayList<>();
        for (OrderLine line : confirmed) {
            if (line.isDraft()) {
                continue;
            }
            List<OrderLinePriceAdjustment> own = adjusted.getOrDefault(line.getId(), List.of());
            EffectivePrice price = EffectivePrice.of(line, own, inherited.get(line.getId()), lineage.get(line.getId()));
            BigDecimal net = line.getQuantity().subtract(deducted.getOrDefault(line.getId(), BigDecimal.ZERO));
            // an offer is a free unit: a line reversed part way to a positive price is a discounted line
            if (net.signum() <= 0 || !"OFFER".equals(price.origin()) || price.unitPrice().amount().signum() != 0) {
                continue;
            }
            OrderLinePriceAdjustment offer = !own.isEmpty() && own.getLast().getKind() == AdjustmentKind.OFFER
                    ? own.getLast() : lineage.get(line.getId());
            OrderLine round = lines.roundLineOf(line.getId()).flatMap(lines::findById)
                    .orElseThrow(() -> new IllegalStateException("Line " + line.getId() + " has no round line"));
            offers.add(new OfferFacts.OfferLine(line.getId(), line.getItemId(),
                    line.snapshot().orElseThrow().releaseId(), round.getId(), round.getQuantity(), net,
                    offer.getOperatorEmployeeId(), offer.getApproverEmployeeId(),
                    line.promotion().map(PromotionRef::code).orElse(null)));
        }
        return offers;
    }

    private Map<UUID, List<OrderLinePriceAdjustment>> adjustments(List<UUID> orderIds) {
        return adjustments.findByOrderIdInOrderByLineIdAscSeqAsc(orderIds).stream()
                .collect(Collectors.groupingBy(OrderLinePriceAdjustment::getLineId));
    }
}
