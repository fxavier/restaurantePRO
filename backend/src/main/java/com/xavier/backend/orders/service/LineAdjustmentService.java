package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.AdjustLine;
import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.SettledAdjustmentCommand;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.EffectivePrice;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderLinePriceAdjustment;
import com.xavier.backend.orders.model.OrderLineSplit;
import com.xavier.backend.orders.model.dto.DiscountRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.orders.repository.OrderLinePriceAdjustmentRepository;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderLineSplitRepository;
import com.xavier.backend.promotions.LoyaltyApi;
import com.xavier.backend.promotions.PromotionsApi;
import com.xavier.backend.promotions.RedeemCommand;
import com.xavier.backend.promotions.RedemptionResult;
import com.xavier.backend.promotions.model.dto.DiscountLimits;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discounts after confirmation (D105, D106, D112): each adjusted line gets an {@link OrderLinePriceAdjustment} fact
 * that sets its effective unit price ({@code 0 ≤ price ≤ list}, D101). A quantity below the line's net quantity
 * first
 * splits it ({@link OrderLineSplit}): the adjustment applies to the new line, which has no round, so production and
 * stock never see it again. The POS route ({@link #authorize}, then {@link #adjust}) needs billing lock NONE; the
 * settled route ({@link #adjustSettled}) runs under settlement's {@code order_settlement} lock, which checked the
 * headroom, and refuses only INVOICE. Both claim the order's version, so two adjustments of one order queue and the
 * second gets 409.
 *
 * <p>Limits (D106), measured cumulatively against the list price: up to the store's free limit the operator alone,
 * up to its maximum with an approver holding {@code ORDER_DISCOUNT_APPROVE}, above it 422
 * {@code DISCOUNT_LIMIT_EXCEEDED}. An OFFER always needs the approver; a REWARD none (the points pay for it).
 */
@Service
public class LineAdjustmentService {

    static final String ENTITY = "OrderLinePriceAdjustment";

    private final OrderLineRepository lines;
    private final OrderLinePriceAdjustmentRepository adjustments;
    private final OrderLineSplitRepository splits;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final PromotionsApi promotions;
    private final LoyaltyApi loyalty;
    private final LineFamilies families;
    private final RewardLineage rewards;
    private final ApproverVerifier approvers;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final OrderChanges changes;
    private final Clock clock;

    LineAdjustmentService(OrderLineRepository lines, OrderLinePriceAdjustmentRepository adjustments,
                          OrderLineSplitRepository splits, OrderLookup lookup, OrderViews views,
                          PromotionsApi promotions, LoyaltyApi loyalty, LineFamilies families, RewardLineage rewards,
                          ApproverVerifier approvers, AccessPolicy accessPolicy, ApplicationEventPublisher events,
                          OrderChanges changes, Clock clock) {
        this.lines = lines;
        this.adjustments = adjustments;
        this.splits = splits;
        this.lookup = lookup;
        this.views = views;
        this.promotions = promotions;
        this.loyalty = loyalty;
        this.families = families;
        this.rewards = rewards;
        this.approvers = approvers;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.changes = changes;
        this.clock = clock;
    }

    /** The permission an adjustment of {@code kind} needs (D106, D134). */
    static Permission permissionFor(AdjustmentKind kind) {
        return switch (kind) {
            case DISCOUNT, REVERSAL -> Permission.ORDER_DISCOUNT;
            case OFFER -> Permission.ORDER_OFFER;
            case REWARD -> Permission.LOYALTY_REDEEM;
        };
    }

    /**
     * Phase 1, outside any transaction: the kind's permission on the order's store (404, 403), then, when the POS sent
     * an approver, that approver verified there for {@code ORDER_DISCOUNT_APPROVE} and not the operator. Whether one
     * is needed is decided in phase 2, against the line's price under the claimed version.
     *
     * @return the verified approver, or {@code null} when none was sent
     * @throws ApprovalDeniedException 403 for a refused approver
     * @throws OrderRuleException      422 {@code SELF_APPROVAL}
     */
    public UUID authorize(UUID orderId, AdjustmentKind kind, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(permissionFor(kind));
        lookup.requireSessionStore(pos, orderId);
        boolean noCode = approverCode == null || approverCode.isBlank();
        boolean noPin = approverPin == null || approverPin.isBlank();
        if (noCode && noPin) {
            return null;
        }
        if (noCode || noPin) {
            throw new ApprovalDeniedException();
        }
        UUID approverId = approvers.verify(approverCode, approverPin, Permission.ORDER_DISCOUNT_APPROVE,
                pos.storeId());
        if (approverId.equals(pos.employeeId())) {
            throw OrderRuleException.selfApproval();
        }
        return approverId;
    }

    /**
     * Phase 2 of the POS route: the adjustments, under the order's version, with billing lock NONE.
     *
     * @throws OrderRuleException 422 {@code ORDER_IN_SETTLEMENT}, {@code ORDER_FROZEN},
     *                            {@code DISCOUNT_LIMIT_EXCEEDED},
     *                            {@code PRICE_INCREASE_NOT_ALLOWED}, {@code LOYALTY_CUSTOMER_REQUIRED},
     *                            {@code REWARD_NEEDS_SINGLE_UNIT}, {@code REWARD_ALREADY_REDEEMED},
     *                            {@code QUANTITY_EXCEEDS_NET}
     */
    @Transactional
    public OrderView adjust(UUID orderId, DiscountRequest request, UUID approverId) {
        PosContext pos = accessPolicy.requirePos(permissionFor(request.kind()));
        lookup.requireSessionStore(pos, orderId);
        Order order = lookup.claim(orderId, request.orderVersion());
        order.requireBillReducible();
        Money unitPrice = request.unitPrice() == null ? null : Money.of(request.unitPrice(), order.currency());
        record(order, new Adjustment(request.kind(), request.percent(), unitPrice, request.rewardCode(),
                request.reason(), pos.employeeId(), approverId), request.lines().stream()
                .map(line -> new AdjustLine(line.lineId(), line.quantity())).toList());
        return views.of(order);
    }

    /**
     * The settled route (D105), in the caller's transaction: settlement holds {@code order_settlement} and checked the
     * headroom, so only INVOICE refuses here, never PAYMENT; a REVERSAL, which raises the bill, is refused.
     *
     * @throws OrderRuleException 422 {@code ORDER_FROZEN}, {@code PRICE_INCREASE_NOT_ALLOWED},
     *                            {@code DISCOUNT_LIMIT_EXCEEDED}, {@code SELF_APPROVAL}, …
     */
    public OrderBillingView adjustSettled(SettledAdjustmentCommand command, UUID approverEmployeeId) {
        if (approverEmployeeId != null && approverEmployeeId.equals(command.operatorEmployeeId())) {
            throw OrderRuleException.selfApproval();
        }
        Order order = lookup.claim(command.orderId(), command.expectedVersion());
        order.requireChangeable();
        if (command.kind() == AdjustmentKind.REVERSAL) {
            throw OrderRuleException.priceIncreaseNotAllowed(command.lines().getFirst().lineId());
        }
        record(order, new Adjustment(command.kind(), command.percent(), command.unitPrice(), command.rewardCode(),
                command.reason(), command.operatorEmployeeId(), approverEmployeeId), command.lines());
        return views.billing(order);
    }

    /** What every adjusted line gets. */
    private record Adjustment(AdjustmentKind kind, BigDecimal percent, Money unitPrice, String rewardCode,
                              String reason, UUID operatorId, UUID approverId) {
    }

    private void record(Order order, Adjustment adjustment, List<AdjustLine> requested) {
        DiscountLimits limits = promotions.discountLimits(order.getStoreId());
        Map<UUID, BigDecimal> perLine = new LinkedHashMap<>();
        requested.forEach(line -> perLine.merge(line.lineId(), line.quantity() == null ? BigDecimal.ZERO
                : line.quantity(), BigDecimal::add));
        Instant now = clock.instant();
        perLine.forEach((lineId, asked) -> recordLine(order, adjustment, limits, lineId,
                asked.signum() == 0 ? null : asked, now));
    }

    private void recordLine(Order order, Adjustment adjustment, DiscountLimits limits, UUID lineId,
                            BigDecimal asked, Instant now) {
        OrderLine line = lines.findByIdAndOrderId(lineId, order.getId())
                .orElseThrow(() -> new OrderNotFoundException("OrderLine", lineId));
        BigDecimal deducted = views.deducted(line);
        BigDecimal net = line.getQuantity().subtract(deducted);
        BigDecimal quantity = asked == null ? net : asked;
        if (quantity.signum() <= 0) {
            throw OrderRuleException.quantityExceedsNet(lineId, net);
        }
        line.requireAvailable(deducted, quantity);
        boolean rewarded = rewards.isRewarded(lineId);
        if (adjustment.kind() == AdjustmentKind.REWARD) {
            requireRewardable(order, lineId, quantity, rewarded);
        }
        List<OrderLinePriceAdjustment> history = adjustments.findByLineIdOrderBySeq(lineId);
        EffectivePrice price = EffectivePrice.of(line, history);
        Money current = price.unitPrice();
        boolean approverNeeded = adjustment.kind() != AdjustmentKind.REWARD
                && checkedPrice(adjustment, line, rewarded, price, limits) == null;
        if (approverNeeded && adjustment.approverId() == null) {
            throw new ApprovalDeniedException();
        }

        UUID adjustmentId = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        OrderLine target = line;
        int seq = history.size() + 1;
        if (quantity.compareTo(net) < 0) {
            UUID splitId = UuidVersion7Strategy.INSTANCE.generateUuid(null);
            target = lines.saveAndFlush(OrderLine.splitCopy(line, current, quantity, splitId,
                    adjustment.operatorId(), now));
            splits.saveAndFlush(OrderLineSplit.of(splitId, line, target, adjustmentId, adjustment.operatorId(), now));
            seq = 1;
        }
        Money newPrice;
        UUID loyaltyEntry = null;
        if (adjustment.kind() == AdjustmentKind.REWARD) {
            RedemptionResult redeemed = loyalty.redeem(new RedeemCommand(order.getCustomerId(),
                    adjustment.rewardCode(), target.getId(), order.getId(), order.getStoreId(),
                    order.getBusinessDay(), line.getItemId(), families.familyOf(order.getStoreId(),
                    line.snapshot().orElseThrow().releaseId(), line.getItemId()), current));
            newPrice = redeemed.newUnitPrice();
            loyaltyEntry = redeemed.entryId();
            if (newPrice.amount().compareTo(current.amount()) >= 0) {
                throw OrderRuleException.priceIncreaseNotAllowed(lineId);
            }
        } else {
            newPrice = newPrice(adjustment, line, rewarded, price);
        }
        OrderLinePriceAdjustment fact = adjustments.saveAndFlush(OrderLinePriceAdjustment.of(adjustmentId, target,
                seq, new OrderLinePriceAdjustment.Change(adjustment.kind(), quantity, adjustment.percent(), current,
                        newPrice, adjustment.rewardCode(), loyaltyEntry, adjustment.reason(), adjustment.operatorId(),
                        adjustment.approverId()), now));
        events.publishEvent(new LineDiscountsRecorded(order.getId(), order.getStoreId(),
                LineDiscountsRecorded.ADJUSTMENT, fact.getId()));
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", order.getId().toString());
        details.put("lineId", target.getId().toString());
        details.put("kind", adjustment.kind().name());
        details.put("quantity", quantity.toPlainString());
        details.put("previousUnitPrice", current.amount().toPlainString());
        details.put("unitPrice", newPrice.amount().toPlainString());
        if (target != line) {
            details.put("splitFromLineId", line.getId().toString());
        }
        changes.recordWithReason("ORDER_LINE_PRICE_ADJUSTED", ENTITY, fact.getId(), order, fact.getApproverEmployeeId(),
                fact.getReason(), details);
    }

    /** D112, D112a: a reward needs the order's customer, exactly one unit, and a line that carries none yet. */
    private static void requireRewardable(Order order, UUID lineId, BigDecimal quantity, boolean rewarded) {
        if (rewarded) {
            throw OrderRuleException.rewardAlreadyRedeemed(lineId);
        }
        if (order.getCustomerId() == null) {
            throw OrderRuleException.loyaltyCustomerRequired(order.getId());
        }
        if (quantity.compareTo(BigDecimal.ONE) != 0) {
            throw OrderRuleException.rewardNeedsSingleUnit(lineId);
        }
    }

    /**
     * The new price of a DISCOUNT, OFFER or REVERSAL when the operator alone may set it, {@code null} when it needs an
     * approver (D106).
     *
     * @throws OrderRuleException 422 {@code PRICE_INCREASE_NOT_ALLOWED}, {@code DISCOUNT_LIMIT_EXCEEDED}
     */
    private static Money checkedPrice(Adjustment adjustment, OrderLine line, boolean rewarded,
                                      EffectivePrice price, DiscountLimits limits) {
        Money newPrice = newPrice(adjustment, line, rewarded, price);
        return switch (adjustment.kind()) {
            case OFFER -> null;
            case REVERSAL -> newPrice;
            default -> {
                BigDecimal off = adjustment.percent() != null
                        ? EffectivePrice.percentOffAsked(price.listUnitPrice(), price.unitPrice(), adjustment.percent())
                        : EffectivePrice.percentOff(price.listUnitPrice(), newPrice);
                if (off.compareTo(limits.maxPercent()) > 0) {
                    throw OrderRuleException.discountLimitExceeded(line.getId(), off.setScale(2,
                            java.math.RoundingMode.HALF_UP), limits.maxPercent());
                }
                yield off.compareTo(limits.freeLimitPercent()) > 0 ? null : newPrice;
            }
        };
    }

    /**
     * D101: a percent off the current price, HALF_UP per unit; a given price; 0 for an offer; for a reversal a given
     * price or the list price. A discount or offer must lower the price; a reversal must raise it, at most to the
     * list price, and never on a rewarded line.
     */
    private static Money newPrice(Adjustment adjustment, OrderLine line, boolean rewarded, EffectivePrice price) {
        Money current = price.unitPrice();
        Money list = price.listUnitPrice();
        Money next = switch (adjustment.kind()) {
            case DISCOUNT -> adjustment.percent() != null
                    ? EffectivePrice.afterPercentOff(current, adjustment.percent())
                    : Objects.requireNonNull(adjustment.unitPrice(), "unitPrice");
            case OFFER -> Money.of(BigDecimal.ZERO, current.currency());
            case REVERSAL -> adjustment.unitPrice() == null ? list : adjustment.unitPrice();
            case REWARD -> throw new IllegalStateException("A reward's price comes from loyalty");
        };
        boolean lowers = next.amount().compareTo(current.amount()) < 0;
        if (adjustment.kind() == AdjustmentKind.REVERSAL) {
            if (lowers || next.amount().compareTo(current.amount()) == 0
                    || next.amount().compareTo(list.amount()) > 0 || rewarded) {
                throw OrderRuleException.priceIncreaseNotAllowed(line.getId());
            }
        } else if (!lowers || next.amount().signum() < 0) {
            throw OrderRuleException.priceIncreaseNotAllowed(line.getId());
        }
        return next;
    }
}
