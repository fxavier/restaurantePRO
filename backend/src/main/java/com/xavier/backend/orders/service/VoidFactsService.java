package com.xavier.backend.orders.service;

import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.VoidFacts;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.model.LineSnapshot;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.OrderLineVoid;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.orders.repository.OrderLineVoidRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Reads void facts for reporting (D84) through {@code OrdersApi}, in the caller's read-only transaction. Facts are
 * immutable, so each pull returns the same answer; nothing here authorizes (D27).
 */
@Service
public class VoidFactsService {

    private final OrderLineVoidRepository voids;
    private final OrderLineRepository lines;
    private final OrderLookup lookup;

    VoidFactsService(OrderLineVoidRepository voids, OrderLineRepository lines, OrderLookup lookup) {
        this.voids = voids;
        this.lines = lines;
        this.lookup = lookup;
    }

    public VoidFacts facts(UUID voidId) {
        OrderLineVoid voided = voids.findById(voidId)
                .orElseThrow(() -> new OrderNotFoundException(LineVoidService.ENTITY, voidId));
        Order order = lookup.load(voided.getOrderId());
        OrderLine line = lines.findById(voided.getLineId()).orElseThrow();
        LineSnapshot snapshot = line.snapshot().orElseThrow();
        return new VoidFacts(voided.getId(), order.getId(), order.getStoreId(), order.getBusinessDay(),
                voided.getVoidedAt(), new VoidFacts.VoidedLine(line.getId(), line.getItemId(), snapshot.itemCode(),
                snapshot.billedDescription(), snapshot.unitPrice(), snapshot.taxCode(), snapshot.taxRatePercent(),
                snapshot.releaseId()), voided.getQuantity(),
                LineAmounts.of(snapshot.unitPrice(), voided.getQuantity(), snapshot.taxRatePercent()).gross(),
                voided.getReason(), voided.isWasted(), voided.getOperatorEmployeeId(),
                voided.getApproverEmployeeId(), voids.wasAfterDispatch(voidId));
    }

    public Page<UUID> ids(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return voids.findIdsByStoreAndBusinessDay(storeId, from, to,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));
    }
}
