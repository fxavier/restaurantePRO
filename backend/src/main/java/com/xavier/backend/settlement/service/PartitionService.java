package com.xavier.backend.settlement.service;

import com.xavier.backend.shared.persistence.StoreProbe;
import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.PartitionAmounts;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.BillPartition;
import com.xavier.backend.settlement.model.BillPartition.ItemShare;
import com.xavier.backend.settlement.model.LineSplit;
import com.xavier.backend.settlement.model.PartitionKind;
import com.xavier.backend.settlement.model.dto.CancelPartitionRequest;
import com.xavier.backend.settlement.model.dto.CreatePartitionRequest;
import com.xavier.backend.settlement.model.dto.EqualPartsRequest;
import com.xavier.backend.settlement.model.dto.PartitionLineRequest;
import com.xavier.backend.settlement.model.dto.PartitionView;
import com.xavier.backend.settlement.repository.BillPartitionRepository;
import com.xavier.backend.settlement.repository.PartitionRef;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.settlement.repository.PaymentRepository;
import com.xavier.backend.settlement.service.Bills.Bill;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bill partitions, "conta dividida" (D67, D68): ITEMS and AMOUNT partitions, equal parts, cancel and the list. Every
 * command authorizes on the order's store alone (D42), then takes {@code order_settlement} (D51, D76) and decides on
 * the bill as committed, so two terminals splitting one bill are serialized: Σ LIVE quantity per line ≤ net quantity
 * holds although no constraint can express it. The first partition locks the order for payment (D49).
 */
@Service
public class PartitionService {

    static final String PARTITION = "BillPartition";

    private final StoreProbe storeProbe;
    private final AccessPolicy accessPolicy;
    private final SettlementLocks locks;
    private final Bills bills;
    private final SettlementViews views;
    private final BillPartitionRepository partitions;
    private final PaymentRepository payments;
    private final PaymentAttemptRepository attempts;
    private final PaymentLocks paymentLocks;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final SettlementAudit audit;
    private final Clock clock;

    PartitionService(StoreProbe storeProbe, AccessPolicy accessPolicy, SettlementLocks locks, Bills bills, SettlementViews views,
                     BillPartitionRepository partitions, PaymentRepository payments,
                     PaymentAttemptRepository attempts, PaymentLocks paymentLocks, OrdersApi orders,
                     FiscalApi fiscal, SettlementAudit audit, Clock clock) {
        this.storeProbe = storeProbe;
        this.accessPolicy = accessPolicy;
        this.locks = locks;
        this.bills = bills;
        this.views = views;
        this.partitions = partitions;
        this.payments = payments;
        this.attempts = attempts;
        this.paymentLocks = paymentLocks;
        this.orders = orders;
        this.fiscal = fiscal;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * An ITEMS or AMOUNT partition at the client's order version. The business rules are checked before the version,
     * so of two terminals splitting the same quantity the second learns why (D91).
     *
     * @throws SettlementRuleException 422 {@code ORDER_NOT_OPEN}, {@code ORDER_FROZEN},
     *         {@code QUANTITY_EXCEEDS_NET}, {@code QUANTITY_ALREADY_PARTITIONED}, {@code AMOUNT_EXCEEDS_RESIDUAL},
     *         {@code SPLIT_ROUNDING_LIMIT}, {@code INVALID_AMOUNT}
     */
    @Transactional
    public PartitionView create(UUID orderId, CreatePartitionRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.BILL_SPLIT);
        Bill bill = lockedBill(pos, orderId);
        Instant now = clock.instant();
        BillPartition partition = PartitionKind.ITEMS.name().equals(request.kind())
                ? items(bill, request, pos, now)
                : amount(bill, request, pos, now);
        OptimisticLocking.requireVersion(OrderBillingView.class, orderId, bill.view().version(),
                request.orderVersion());
        return saved(bill, List.of(partition)).getFirst();
    }

    /**
     * The residual balance in {@code parts} AMOUNT partitions sharing a group (D68: {@code Money.allocate}).
     *
     * @throws SettlementRuleException 422 {@code NOTHING_TO_SPLIT}, {@code ORDER_NOT_OPEN}, {@code ORDER_FROZEN}
     */
    @Transactional
    public List<PartitionView> equalParts(UUID orderId, EqualPartsRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.BILL_SPLIT);
        Bill bill = lockedBill(pos, orderId);
        Money residual = bill.residualHeadroom();
        int parts = request.parts();
        if (residual.amount().movePointRight(residual.currency().getDefaultFractionDigits())
                .compareTo(BigDecimal.valueOf(parts)) < 0) {
            throw SettlementRuleException.nothingToSplit(orderId, residual, parts);
        }
        OptimisticLocking.requireVersion(OrderBillingView.class, orderId, bill.view().version(),
                request.orderVersion());
        UUID group = UUID.randomUUID();
        Instant now = clock.instant();
        List<BillPartition> created = new ArrayList<>();
        List<Money> amounts = PartitionAmounts.equalParts(residual, parts);
        for (int part = 1; part <= parts; part++) {
            created.add(BillPartition.amount(orderId, bill.storeId(), amounts.get(part - 1), group, part, parts,
                    part + "/" + parts, pos.employeeId(), now));
        }
        return saved(bill, created);
    }

    /**
     * LIVE → CANCELLED at the client's partition version: its quantities and amount return to the pool (D68).
     *
     * @throws SettlementRuleException 422 {@code PARTITION_IN_USE}, {@code ORDER_FROZEN}, {@code SPLIT_ROUNDING_LIMIT}
     */
    @Transactional
    public PartitionView cancel(UUID partitionId, CancelPartitionRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.BILL_SPLIT);
        PartitionRef ref = authorized(pos, partitionId);
        locks.lockOrder(ref.orderId());
        Bill bill = bills.of(ref.orderId());
        BillPartition partition = bill.partition(partitionId).orElseThrow();
        OptimisticLocking.requireVersion(BillPartition.class, partitionId, partition.currentVersion(), request.version());
        if (partition.isLive()) {
            bill.requireOpen().requireNotFrozen();
            requireNotInUse(partition);
            for (var line : partition.getLines()) {
                bill.split(line.getOrderLineId()).ifPresent(split -> split.requireCancellable(partitionId));
            }
        }
        partition.cancel(request.reason(), pos.employeeId(), clock.instant());
        partitions.saveAndFlush(partition);
        audit.record("BILL_PARTITION_CANCELLED", PARTITION, partitionId, bill.storeId(),
                Map.of("orderId", ref.orderId().toString(), "reason", request.reason()));
        audit.orderChanged(bill.storeId(), ref.orderId());
        paymentLocks.releaseIfIdle(ref.orderId());
        return views.partition(bills.of(ref.orderId()), partition);
    }

    @Transactional(readOnly = true)
    public List<PartitionView> list(UUID orderId) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VIEW);
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        return views.partitions(bills.of(orderId));
    }

    private BillPartition items(Bill bill, CreatePartitionRequest request, PosContext pos, Instant now) {
        List<PartitionLineRequest> lines = request.lines() == null ? List.of() : request.lines();
        if (lines.isEmpty() || request.amount() != null) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT,
                    "An ITEMS partition has lines and no amount.");
        }
        Set<UUID> seen = new HashSet<>();
        List<ItemShare> shares = new ArrayList<>();
        Money due = Money.of(BigDecimal.ZERO, bill.due().currency());
        for (PartitionLineRequest line : lines) {
            if (!seen.add(line.lineId())) {
                throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT,
                        "Line " + line.lineId() + " appears twice.");
            }
            BillingLine billing = bill.line(line.lineId()).orElseThrow(() ->
                    SettlementRuleException.quantityExceedsNet(line.lineId(), BigDecimal.ZERO, line.quantity()));
            LineSplit split = bill.split(billing);
            var share = split.take(line.quantity());
            shares.add(new ItemShare(line.lineId(), line.quantity(), share, billing.taxCode(),
                    billing.ratePercent()));
            due = due.plus(share.gross());
        }
        requireWithinResidual(bill, due);
        return BillPartition.items(bill.view().orderId(), bill.storeId(), shares, request.label(),
                pos.employeeId(), now);
    }

    private BillPartition amount(Bill bill, CreatePartitionRequest request, PosContext pos, Instant now) {
        if (request.amount() == null || (request.lines() != null && !request.lines().isEmpty())) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT,
                    "An AMOUNT partition has an amount and no lines.");
        }
        Money amount = PaymentAttemptService.money(request.amount(), bill.due().currency());
        requireWithinResidual(bill, amount);
        return BillPartition.amount(bill.view().orderId(), bill.storeId(), amount, null, null, null,
                request.label(), pos.employeeId(), now);
    }

    /** The ORDER target never goes below what it was paid or has in flight (D69). */
    private static void requireWithinResidual(Bill bill, Money due) {
        Money headroom = bill.residualHeadroom();
        if (due.amount().compareTo(headroom.amount()) > 0) {
            throw SettlementRuleException.amountExceedsResidual(bill.view().orderId(), due, headroom);
        }
    }

    private void requireNotInUse(BillPartition partition) {
        UUID id = partition.getId();
        boolean documented = fiscal.documentsOfOrder(partition.getOrderId()).stream()
                .anyMatch(d -> "PARTITION".equals(d.sourceType()) && id.equals(d.sourceId()));
        if (payments.existsByPartitionId(id)
                || attempts.existsByPartitionIdAndStatusIn(id, com.xavier.backend.settlement.model.AttemptStatus.OPEN)
                || documented) {
            throw SettlementRuleException.partitionInUse(id);
        }
    }

    private List<PartitionView> saved(Bill bill, List<BillPartition> created) {
        UUID orderId = bill.view().orderId();
        orders.lockForPayment(orderId);
        partitions.saveAllAndFlush(created);
        for (BillPartition partition : created) {
            audit.record("BILL_PARTITION_CREATED", PARTITION, partition.getId(), bill.storeId(), Map.of(
                    "orderId", orderId.toString(), "kind", partition.getKind().name(),
                    "due", partition.due().amount().toPlainString()));
        }
        audit.orderChanged(bill.storeId(), orderId);
        Bill after = bills.of(orderId);
        return created.stream().map(partition -> views.partition(after, partition)).toList();
    }

    /** D42 (b) then D51: the order's store only (404, 403), then its settlement lock, then the bill as committed. */
    private Bill lockedBill(PosContext pos, UUID orderId) {
        accessPolicy.requireSessionStore(pos, orders.storeOf(orderId), PaymentAttemptService.ORDER, orderId);
        locks.lockOrder(orderId);
        return bills.of(orderId).requireOpen().requireNotFrozen();
    }

    /** D42: the partition's store across every store (D168), 404 then 403, before its ref is read. */
    PartitionRef authorized(PosContext pos, UUID partitionId) {
        UUID storeId = storeProbe.storeOf("settlement.bill_partition", partitionId)
                .orElseThrow(() -> new SettlementNotFoundException(PARTITION, partitionId));
        accessPolicy.requireSessionStore(pos, storeId, PARTITION, partitionId);
        return partitions.findRefById(partitionId).orElseThrow();
    }
}
