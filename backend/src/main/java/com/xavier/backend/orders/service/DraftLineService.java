package com.xavier.backend.orders.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.orders.exception.OrderNotFoundException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.CatalogPricing;
import com.xavier.backend.orders.model.Order;
import com.xavier.backend.orders.model.OrderLine;
import com.xavier.backend.orders.model.dto.AddLineRequest;
import com.xavier.backend.orders.model.dto.DeleteLineRequest;
import com.xavier.backend.orders.model.dto.LineView;
import com.xavier.backend.orders.model.dto.UpdateLineRequest;
import com.xavier.backend.orders.repository.OrderLineRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DRAFT lines (r2-decisions §4 orders): not facts, so they may be edited or deleted, each under the line's own
 * version. They never change the order's version (PRD S13). A new line or variant must be in the published catalog;
 * the price shown is a preview, fixed only at confirmation (§8.4).
 */
@Service
public class DraftLineService {

    static final String ENTITY = "OrderLine";

    private final OrderLineRepository lines;
    private final OrderLookup lookup;
    private final CatalogApi catalog;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    DraftLineService(OrderLineRepository lines, OrderLookup lookup, CatalogApi catalog, AccessPolicy accessPolicy,
                     OrderChanges changes, OrderViews views, Clock clock) {
        this.lines = lines;
        this.lookup = lookup;
        this.catalog = catalog;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.views = views;
        this.clock = clock;
    }

    @Transactional
    public LineView add(UUID orderId, AddLineRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        order.requireChangeable();
        lookup.requireTableUnlocked(order);
        CatalogSnapshot current = inCatalog(order, request.itemId(), request.variantId());
        OrderLine line = lines.saveAndFlush(OrderLine.draft(orderId, request.itemId(), request.variantId(),
                request.quantity(), request.notes(), pos.employeeId(), clock.instant()));
        changed("ORDER_LINE_DRAFTED", order, line);
        return views.draftView(line, current);
    }

    @Transactional
    public LineView update(UUID orderId, UUID lineId, UpdateLineRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        order.requireChangeable();
        OrderLine line = draft(orderId, lineId, request.version());
        CatalogSnapshot current = inCatalog(order, line.getItemId(), request.variantId());
        line.edit(request.variantId(), request.quantity(), request.notes());
        line = lines.saveAndFlush(line);
        changed("ORDER_LINE_DRAFT_CHANGED", order, line);
        return views.draftView(line, current);
    }

    @Transactional
    public void delete(UUID orderId, UUID lineId, DeleteLineRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        order.requireChangeable();
        OrderLine line = draft(orderId, lineId, request.version());
        lines.delete(line);
        lines.flush();
        changed("ORDER_LINE_DRAFT_DELETED", order, line);
    }

    /** The line of this order (404), at the client's version (409), still a draft (409 {@code LINE_NOT_DRAFT}). */
    private OrderLine draft(UUID orderId, UUID lineId, long version) {
        OrderLine line = lines.findByIdAndOrderId(lineId, orderId)
                .orElseThrow(() -> new OrderNotFoundException(ENTITY, lineId));
        OptimisticLocking.requireVersion(OrderLine.class, lineId, line.getVersion(), version);
        line.requireDraft();
        return line;
    }

    private CatalogSnapshot inCatalog(Order order, UUID itemId, UUID variantId) {
        CatalogSnapshot current = catalog.currentSnapshot(order.getStoreId());
        if (CatalogPricing.previewPrice(current, itemId, variantId).isEmpty()) {
            throw OrderRuleException.itemNotInCatalog(itemId, variantId);
        }
        return current;
    }

    private void changed(String action, Order order, OrderLine line) {
        Map<String, Object> details = new HashMap<>();
        details.put("orderId", order.getId().toString());
        details.put("itemId", line.getItemId().toString());
        details.put("quantity", line.getQuantity().toPlainString());
        changes.record(action, ENTITY, line.getId(), order.getStoreId(), order.getId(), details);
    }
}
