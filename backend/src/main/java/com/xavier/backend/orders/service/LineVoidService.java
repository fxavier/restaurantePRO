package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderLineVoid;
import com.xavier.backend.orders.model.dto.VoidLineRequest;
import com.xavier.backend.orders.model.dto.VoidView;
import com.xavier.backend.orders.model.event.Line;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderLineVoidRepository;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Voids (spec §4 "anulação após envio exige motivo, autorização e comunicação à produção", D40, D49). Phase 1
 * ({@link #authorize}) verifies the approver with no transaction open, so the BCrypt check holds no order row; phase 2
 * ({@link #voidLine}) claims the order's version, checks the net quantity under it, records the void fact and
 * publishes {@link OrderLinesVoided} for production's VOID ticket, in one transaction.
 */
@Service
public class LineVoidService {

    static final String ENTITY = "OrderLineVoid";

    private final OrderLineRepository lines;
    private final OrderLineVoidRepository voids;
    private final OrderLookup lookup;
    private final OrderViews views;
    private final ApproverVerifier approvers;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final OrderChanges changes;
    private final Clock clock;

    LineVoidService(OrderLineRepository lines, OrderLineVoidRepository voids, OrderLookup lookup, OrderViews views,
                    ApproverVerifier approvers, AccessPolicy accessPolicy, ApplicationEventPublisher events,
                    OrderChanges changes, Clock clock) {
        this.lines = lines;
        this.voids = voids;
        this.lookup = lookup;
        this.views = views;
        this.approvers = approvers;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * Phase 1, outside any transaction: {@code ORDER_VOID} on the order's store (404, 403), then an approver holding
     * {@code ORDER_VOID_APPROVE} there who is not the operator. A wrong PIN counts toward the approver's lockout.
     *
     * @throws ApprovalDeniedException 403 for a missing or refused approver
     * @throws OrderRuleException      422 {@code SELF_APPROVAL}
     */
    public Approval authorize(UUID orderId, String approverCode, String approverPin) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VOID);
        lookup.requireSessionStore(pos, orderId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        UUID approverId = approvers.verify(approverCode, approverPin, Permission.ORDER_VOID_APPROVE, pos.storeId());
        if (approverId.equals(pos.employeeId())) {
            throw OrderRuleException.selfApproval();
        }
        return new Approval(orderId, approverId);
    }

    /**
     * Phase 2: the void fact, under the order's version.
     *
     * @throws OrderRuleException 422 {@code ORDER_IN_SETTLEMENT}, {@code ORDER_FROZEN}, {@code QUANTITY_EXCEEDS_NET}
     */
    @Transactional
    public VoidView voidLine(UUID orderId, VoidLineRequest request, Approval approval) {
        approval.requireFor(orderId);
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_VOID);
        lookup.requireSessionStore(pos, orderId);
        Order order = lookup.claim(orderId, request.orderVersion());
        order.requireBillReducible();
        OrderLine line = lines.findByIdAndOrderId(request.lineId(), orderId)
                .orElseThrow(() -> new OrderNotFoundException("OrderLine", request.lineId()));
        line.requireAvailable(views.deducted(line), request.quantity());

        OrderLineVoid voided = voids.saveAndFlush(OrderLineVoid.of(line, request.quantity(), request.reason(),
                request.wasted(), pos.employeeId(), approval.approverEmployeeId(), pos.terminalId(), clock.instant()));
        LineSnapshot snapshot = line.snapshot().orElseThrow();
        events.publishEvent(new OrderLinesVoided(orderId, voided.getId(), order.getStoreId(), pos.terminalId(),
                order.getOrderNumber(), order.getTableCode(), new Line(line.getId(), snapshot.itemCode(),
                snapshot.description(), snapshot.variantName(), line.getQuantity(), line.getNotes(),
                snapshot.destination()), voided.getQuantity(), voided.getReason(), voided.getVoidedAt()));
        changes.recordWithReason("ORDER_LINE_VOIDED", ENTITY, voided.getId(), order, approval.approverEmployeeId(),
                voided.getReason(), Map.of("orderId", orderId.toString(), "lineId", line.getId().toString(),
                        "quantity", voided.getQuantity().toPlainString(), "wasted", voided.isWasted()));
        return new VoidView(voided.getId(), orderId, line.getId(), voided.getQuantity(), voided.getReason(),
                voided.isWasted(), voided.getOperatorEmployeeId(), voided.getApproverEmployeeId(),
                voided.getVoidedAt(), views.of(order));
    }
}
