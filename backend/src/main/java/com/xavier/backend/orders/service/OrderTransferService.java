package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderTransfer;
import com.xavier.backend.orders.model.OrderTransferLine;
import com.xavier.backend.orders.model.dto.TransferLineRequest;
import com.xavier.backend.orders.model.dto.TransferRequest;
import com.xavier.backend.orders.model.dto.TransferView;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderTransferLineRepository;
import com.xavier.backend.orders.repository.OrderTransferRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfers confirmed quantities between two OPEN orders of the session store (spec §4 "transferência parcial/total",
 * §8.2, r2-decisions §4 orders). The target gets CONFIRMED copies of the source snapshots with the transfer as origin
 * and no round, so no {@code OrderBatchConfirmed} is published and production never prepares them again. Both orders'
 * versions are claimed in ascending id order ({@link OrderLookup#claim(Map)}), so A→B and B→A at once queue instead of
 * deadlocking: the second finds a moved version and gets 409.
 */
@Service
public class OrderTransferService {

    static final String ENTITY = "OrderTransfer";

    private final OrderLineRepository lines;
    private final OrderTransferRepository transfers;
    private final OrderTransferLineRepository transferLines;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final OrderCancellation cancellation;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final Clock clock;

    OrderTransferService(OrderLineRepository lines, OrderTransferRepository transfers,
                         OrderTransferLineRepository transferLines, OrderLookup lookup, OrderViews views,
                         OrderCancellation cancellation, AccessPolicy accessPolicy, OrderChanges changes,
                         Clock clock) {
        this.lines = lines;
        this.transfers = transfers;
        this.transferLines = transferLines;
        this.lookup = lookup;
        this.views = views;
        this.cancellation = cancellation;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * @throws OrderRuleException 422 {@code SAME_ORDER}, {@code ORDER_IN_SETTLEMENT} (source), {@code ORDER_FROZEN},
     *                            {@code TABLE_LOCKED} (target), {@code QUANTITY_EXCEEDS_NET}
     */
    @Transactional
    public TransferView transfer(UUID sourceId, TransferRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_TRANSFER);
        UUID targetId = request.targetOrderId();
        if (sourceId.equals(targetId)) {
            throw OrderRuleException.sameOrder(sourceId);
        }
        lookup.requireSessionStore(pos, sourceId);
        lookup.requireSessionStore(pos, targetId);
        Map<UUID, Order> claimed = lookup.claim(Map.of(sourceId, request.orderVersion(),
                targetId, request.targetOrderVersion()));
        Order source = claimed.get(sourceId);
        Order target = claimed.get(targetId);
        source.requireBillReducible();
        target.requireChangeable();
        lookup.requireTableUnlocked(target);

        Map<OrderLine, BigDecimal> taken = taken(sourceId, request.lines());
        Instant now = clock.instant();
        OrderTransfer transfer = transfers.saveAndFlush(OrderTransfer.between(source, target, pos.employeeId(),
                pos.terminalId(), now));
        List<TransferView.Line> moved = new ArrayList<>();
        taken.forEach((line, quantity) -> {
            OrderLine copy = lines.saveAndFlush(OrderLine.transferredCopy(line, targetId, quantity, transfer.getId(),
                    pos.employeeId(), now));
            OrderTransferLine fact = transferLines.saveAndFlush(OrderTransferLine.of(transfer, line, copy));
            moved.add(new TransferView.Line(fact.getSourceLineId(), fact.getTargetLineId(), fact.getQuantity()));
        });
        audit(transfer, source, target, moved);
        if (request.cancelSourceWhenEmpty() && cancellation.billsNothing(source)) {
            cancellation.cancel(source, pos.employeeId(), "MERGED_INTO " + targetId);
        }
        return new TransferView(transfer.getId(), sourceId, targetId, transfer.getTransferredAt(), moved,
                views.of(source), views.of(target));
    }

    /** Each requested line of the source with the total asked of it, checked against its net quantity. */
    private Map<OrderLine, BigDecimal> taken(UUID sourceId, List<TransferLineRequest> requested) {
        Map<UUID, BigDecimal> perLine = new LinkedHashMap<>();
        requested.forEach(line -> perLine.merge(line.lineId(), line.quantity(), BigDecimal::add));
        Map<UUID, BigDecimal> deducted = views.deductions(List.of(sourceId));
        Map<OrderLine, BigDecimal> taken = new LinkedHashMap<>();
        perLine.forEach((lineId, quantity) -> {
            OrderLine line = lines.findByIdAndOrderId(lineId, sourceId)
                    .orElseThrow(() -> new OrderNotFoundException("OrderLine", lineId));
            line.requireAvailable(deducted.getOrDefault(lineId, BigDecimal.ZERO), quantity);
            taken.put(line, quantity);
        });
        return taken;
    }

    private void audit(OrderTransfer transfer, Order source, Order target, List<TransferView.Line> moved) {
        Map<String, Object> details = Map.of("sourceOrderId", source.getId().toString(),
                "targetOrderId", target.getId().toString(), "lines", moved.size());
        changes.record("ORDER_LINES_TRANSFERRED", ENTITY, transfer.getId(), source.getStoreId(), source.getId(),
                details);
        changes.notify(target);
    }
}
