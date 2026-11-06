package com.xavier.backend.settlement.service;

import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.BillPartitionLine;
import com.xavier.backend.settlement.model.CreditCharge;
import com.xavier.backend.settlement.model.FinancialState;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.TargetType;
import com.xavier.backend.settlement.repository.BillPartitionRepository;
import com.xavier.backend.settlement.repository.CreditChargeRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.model.Refund;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.repository.RefundRepository;
import com.xavier.backend.settlement.repository.TargetPaid;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An order's bill as settlement computes it (spec §8.3, D69, D74, D77): the due from orders, its partitions, what each
 * target (the ORDER residual, each partition) was paid, net of its refunds, or put on account by a live credit charge,
 * and the attempts still open. Nothing of it is stored.
 */
@Component
class Bills {

    private final OrdersApi orders;
    private final PaymentRepository payments;
    private final BillPartitionRepository partitions;
    private final PaymentAttemptRepository attempts;
    private final CreditChargeRepository charges;
    private final RefundRepository refunds;

    Bills(OrdersApi orders, PaymentRepository payments, BillPartitionRepository partitions,
          PaymentAttemptRepository attempts, CreditChargeRepository charges, RefundRepository refunds) {
        this.refunds = refunds;
        this.orders = orders;
        this.payments = payments;
        this.partitions = partitions;
        this.attempts = attempts;
        this.charges = charges;
    }

    Bill of(UUID orderId) {
        OrderBillingView view = orders.billingView(orderId);
        Money zero = Money.of(BigDecimal.ZERO, view.due().currency());
        Map<UUID, Money> partitionPaid = new HashMap<>();
        Map<UUID, Money> orderPaid = new HashMap<>(); // one key, null: the ORDER target
        add(payments.paidByTarget(orderId), zero, orderPaid, partitionPaid, false);
        add(refunds.refundedByTarget(orderId), zero, orderPaid, partitionPaid, true); // D77: paid is net of refunds
        return new Bill(view, partitions.findByOrderIdOrderById(orderId), orderPaid.getOrDefault(null, zero),
                Map.copyOf(partitionPaid), attempts.findByOrderIdAndStatusInOrderByCreatedAt(orderId,
                AttemptStatus.OPEN), charges.findByOrderIdOrderByCreatedAt(orderId),
                refunds.findByOrderIdOrderById(orderId));
    }

    private static void add(List<TargetPaid> sums, Money zero, Map<UUID, Money> orderPaid,
                            Map<UUID, Money> partitionPaid, boolean refunded) {
        for (TargetPaid sum : sums) {
            Money amount = Money.of(sum.paid(), zero.currency());
            Money signed = refunded ? zero.minus(amount) : amount;
            Map<UUID, Money> target = sum.targetType() == TargetType.PARTITION ? partitionPaid : orderPaid;
            UUID key = sum.targetType() == TargetType.PARTITION ? sum.partitionId() : null;
            target.merge(key, signed, Money::plus);
        }
    }

    /**
     * @param partitions   every partition of the order, LIVE and CANCELLED, oldest first
     * @param orderPaid    Σ payments to the ORDER target (R2 payments included), less its refunds
     * @param partitionPaid Σ payments per partition that was paid, less its refunds
     * @param openAttempts the order's attempts still CREATED, PENDING or UNKNOWN, on any target
     * @param charges      every credit charge of the order, RESERVED, POSTED and RELEASED, oldest first
     * @param refunds      every refund of the order's payments, oldest first
     */
    record Bill(OrderBillingView view, List<BillPartition> partitions, Money orderPaid,
                Map<UUID, Money> partitionPaid, List<PaymentAttempt> openAttempts, List<CreditCharge> charges,
                List<Refund> refunds) {

        Money due() {
            return view.due();
        }

        /** Σ of every payment of the order, on any target, less its refunds. */
        Money paid() {
            return partitionPaid.values().stream().reduce(orderPaid, Money::plus);
        }

        /** Σ of the live (RESERVED and POSTED) credit charges, on any target: "crédito autorizado" (D74). */
        Money credit() {
            return liveCharges().stream().map(CreditCharge::amount).reduce(zero(), Money::plus);
        }

        /** What is still neither paid nor on account. */
        Money balance() {
            return due().minus(paid()).minus(credit());
        }

        FinancialState financialState() {
            return FinancialState.of(due(), paid().plus(credit()));
        }

        List<CreditCharge> liveCharges() {
            return charges.stream().filter(CreditCharge::isLive).toList();
        }

        /** The live charge of a target: the ORDER for {@code partitionId == null}, else that partition. */
        Optional<CreditCharge> liveChargeOn(UUID partitionId) {
            return liveCharges().stream().filter(c -> java.util.Objects.equals(c.getPartitionId(), partitionId))
                    .findFirst();
        }

        /** What a target has on account: its live charge, or zero. */
        Money creditOn(UUID partitionId) {
            return liveChargeOn(partitionId).map(CreditCharge::amount).orElse(zero());
        }

        private Money zero() {
            return Money.of(BigDecimal.ZERO, due().currency());
        }

        UUID storeId() {
            return view.storeId();
        }

        List<BillPartition> live() {
            return partitions.stream().filter(BillPartition::isLive).toList();
        }

        /** D68 amendment: an order that has or had partitions drafts its residual with allocated amounts. */
        boolean everPartitioned() {
            return !partitions.isEmpty();
        }

        Optional<BillPartition> partition(UUID partitionId) {
            return partitions.stream().filter(p -> p.getId().equals(partitionId)).findFirst();
        }

        /** The ORDER target's due: the order's due minus every LIVE partition's (D67). */
        Money residualDue() {
            return live().stream().map(BillPartition::due).reduce(due(), Money::minus);
        }

        /** What the ORDER target still has to collect (D69): neither paid nor on account. */
        Money residualBalance() {
            return residualDue().minus(orderPaid).minus(creditOn(null));
        }

        /** The residual balance less the attempts open on the ORDER target: what a new partition may take. */
        Money residualHeadroom() {
            return openOn(null).stream().map(PaymentAttempt::amount).reduce(residualBalance(), Money::minus);
        }

        Money paidOf(BillPartition partition) {
            return partitionPaid.getOrDefault(partition.getId(), Money.of(BigDecimal.ZERO, due().currency()));
        }

        Money balanceOf(BillPartition partition) {
            return partition.due().minus(paidOf(partition)).minus(creditOn(partition.getId()));
        }

        /** The attempts open on a target: the ORDER for {@code partitionId == null}, else that partition. */
        List<PaymentAttempt> openOn(UUID partitionId) {
            return openAttempts.stream().filter(a -> java.util.Objects.equals(a.getPartitionId(), partitionId))
                    .toList();
        }

        Optional<BillingLine> line(UUID lineId) {
            return view.lines().stream().filter(line -> line.lineId().equals(lineId)).findFirst();
        }

        /** The line as LIVE ITEMS partitions split it, or empty for a line with nothing billable. */
        Optional<LineSplit> split(UUID lineId) {
            return line(lineId).map(this::split);
        }

        /** Its shares in partition {@code created_at} order, then id: the residual's line order (D96). */
        LineSplit split(BillingLine line) {
            List<LineSplit.Piece> pieces = live().stream()
                    .filter(p -> p.getKind() == PartitionKind.ITEMS)
                    .sorted(java.util.Comparator.comparing(BillPartition::getCreatedAt)
                            .thenComparing(BillPartition::getId))
                    .flatMap(p -> p.getLines().stream().filter(l -> l.getOrderLineId().equals(line.lineId()))
                            .map(l -> piece(p, l)))
                    .toList();
            return new LineSplit(line.lineId(), line.unitPrice(), line.ratePercent(), line.netQty(), line.gross(),
                    line.tax(), pieces);
        }

        private static LineSplit.Piece piece(BillPartition partition, BillPartitionLine line) {
            return new LineSplit.Piece(partition.getId(), line.getQuantity(), line.gross(), line.tax());
        }

        /** @throws SettlementRuleException {@code ORDER_NOT_OPEN} for a CLOSED or CANCELLED order */
        Bill requireOpen() {
            if (view.lifecycle() != OrderLifecycle.OPEN) {
                throw new SettlementRuleException(SettlementRule.ORDER_NOT_OPEN,
                        "Order " + view.orderId() + " is " + view.lifecycle() + ".");
            }
            return this;
        }

        boolean frozen() {
            return view.billingLock() == BillingLock.INVOICE;
        }

        /** @throws SettlementRuleException {@code ORDER_FROZEN} once the order's document draft exists (D70) */
        Bill requireNotFrozen() {
            if (frozen()) {
                throw SettlementRuleException.orderFrozen(view.orderId());
            }
            return this;
        }
    }
}
