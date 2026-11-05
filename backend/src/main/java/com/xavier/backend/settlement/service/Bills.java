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
import com.xavier.backend.settlement.model.FinancialState;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.PaymentAttempt;
import com.xavier.backend.settlement.model.TargetType;
import com.xavier.backend.settlement.repository.BillPartitionRepository;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
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
 * An order's bill as settlement computes it (spec §8.3, D69): the due from orders, its partitions, what each target
 * (the ORDER residual, each partition) was paid, and the attempts still open. Nothing of it is stored.
 */
@Component
class Bills {

    private final OrdersApi orders;
    private final PaymentRepository payments;
    private final BillPartitionRepository partitions;
    private final PaymentAttemptRepository attempts;

    Bills(OrdersApi orders, PaymentRepository payments, BillPartitionRepository partitions,
          PaymentAttemptRepository attempts) {
        this.orders = orders;
        this.payments = payments;
        this.partitions = partitions;
        this.attempts = attempts;
    }

    Bill of(UUID orderId) {
        OrderBillingView view = orders.billingView(orderId);
        Money zero = Money.of(BigDecimal.ZERO, view.due().currency());
        Money orderPaid = zero;
        Map<UUID, Money> partitionPaid = new HashMap<>();
        for (TargetPaid paid : payments.paidByTarget(orderId)) {
            Money amount = Money.of(paid.paid(), zero.currency());
            if (paid.targetType() == TargetType.PARTITION) {
                partitionPaid.put(paid.partitionId(), amount);
            } else {
                orderPaid = orderPaid.plus(amount);
            }
        }
        return new Bill(view, partitions.findByOrderIdOrderById(orderId), orderPaid, Map.copyOf(partitionPaid),
                attempts.findByOrderIdAndStatusInOrderByCreatedAt(orderId, AttemptStatus.OPEN));
    }

    /**
     * @param partitions   every partition of the order, LIVE and CANCELLED, oldest first
     * @param orderPaid    Σ payments to the ORDER target (R2 payments included)
     * @param partitionPaid Σ payments per partition that was paid
     * @param openAttempts the order's attempts still CREATED, PENDING or UNKNOWN, on any target
     */
    record Bill(OrderBillingView view, List<BillPartition> partitions, Money orderPaid,
                Map<UUID, Money> partitionPaid, List<PaymentAttempt> openAttempts) {

        Money due() {
            return view.due();
        }

        /** Σ of every payment of the order, on any target. */
        Money paid() {
            return partitionPaid.values().stream().reduce(orderPaid, Money::plus);
        }

        Money balance() {
            return due().minus(paid());
        }

        FinancialState financialState() {
            return FinancialState.of(due(), paid());
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

        /** What the ORDER target still has to collect (D69). */
        Money residualBalance() {
            return residualDue().minus(orderPaid);
        }

        /** The residual balance less the attempts open on the ORDER target: what a new partition may take. */
        Money residualHeadroom() {
            return openOn(null).stream().map(PaymentAttempt::amount).reduce(residualBalance(), Money::minus);
        }

        Money paidOf(BillPartition partition) {
            return partitionPaid.getOrDefault(partition.getId(), Money.of(BigDecimal.ZERO, due().currency()));
        }

        Money balanceOf(BillPartition partition) {
            return partition.due().minus(paidOf(partition));
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

        LineSplit split(BillingLine line) {
            List<LineSplit.Piece> pieces = live().stream()
                    .filter(p -> p.getKind() == PartitionKind.ITEMS)
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
                throw new SettlementRuleException(SettlementRule.ORDER_FROZEN, "Order " + view.orderId()
                        + " has its document draft: its split can no longer change.");
            }
            return this;
        }
    }
}
