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
import com.xavier.backend.orders.model.MenuSelection;
import com.xavier.backend.orders.model.OrderLineComponent;
import com.xavier.backend.orders.model.dto.MacroRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.repository.OrderLineComponentRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 * the price shown is a preview, fixed only at confirmation (§8.4). R4: a menu draft carries its choices (D108), a
 * macro adds several drafts and an opening adds the automatic ones (D109).
 */
@Service
public class DraftLineService {

    static final String ENTITY = "OrderLine";

    private final OrderLineRepository lines;
    private final OrderLineComponentRepository components;
    private final OrderLookup lookup;
    private final CatalogApi catalog;
    private final AccessPolicy accessPolicy;
    private final OrderChanges changes;
    private final OrderViews views;
    private final Clock clock;

    DraftLineService(OrderLineRepository lines, OrderLineComponentRepository components, OrderLookup lookup,
                     CatalogApi catalog, AccessPolicy accessPolicy, OrderChanges changes, OrderViews views,
                     Clock clock) {
        this.lines = lines;
        this.components = components;
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
        List<MenuSelection.Choice> choices = request.components().stream()
                .map(component -> new MenuSelection.Choice(component.groupCode(), component.itemId(),
                        component.variantId())).toList();
        MenuSelection.validate(current, CatalogPricing.itemOf(current, request.itemId(), request.variantId()),
                choices);
        OrderLine line = lines.saveAndFlush(OrderLine.draft(orderId, request.itemId(), request.variantId(),
                request.quantity(), request.notes(), pos.employeeId(), clock.instant()));
        List<OrderLineComponent> chosen = new ArrayList<>();
        for (int position = 0; position < choices.size(); position++) {
            MenuSelection.Choice choice = choices.get(position);
            chosen.add(OrderLineComponent.chosen(line, choice.groupCode(), choice.itemId(), choice.variantId(),
                    position));
        }
        components.saveAllAndFlush(chosen);
        changed("ORDER_LINE_DRAFTED", order, line);
        return views.draftView(order, line, current);
    }

    /**
     * Adds the drafts of the published macro {@code macroCode} (D109), in its order, at the order the POS shows.
     *
     * @throws OrderRuleException 422 {@code MACRO_NOT_PUBLISHED}, {@code ORDER_FROZEN}, {@code TABLE_LOCKED}
     */
    @Transactional
    public OrderView addMacro(UUID orderId, MacroRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        OptimisticLocking.requireVersion(Order.class, orderId, order.getVersion(), request.orderVersion());
        order.requireChangeable();
        lookup.requireTableUnlocked(order);
        CatalogSnapshot current = catalog.currentSnapshot(order.getStoreId());
        CatalogSnapshot.Macro macro = current.macros().stream()
                .filter(candidate -> candidate.code().equals(request.macroCode())).findFirst()
                .orElseThrow(() -> OrderRuleException.macroNotPublished(request.macroCode()));
        for (CatalogSnapshot.MacroEntry entry : macro.entries()) {
            OrderLine line = lines.saveAndFlush(OrderLine.draft(orderId, entry.itemId(), entry.variantId(),
                    entry.quantity(), null, pos.employeeId(), clock.instant()));
            changed("ORDER_LINE_DRAFTED", order, line);
        }
        return views.of(order);
    }

    /**
     * The automatic drafts of a new order (D109), in the opening's transaction: each ACTIVE {@code ORDER_OPEN} item of
     * the order's mode (or of every mode), and for a table each {@code TABLE_OPEN_PER_GUEST} item × guests.
     */
    public void addAutomatic(Order order, CatalogSnapshot current, UUID employeeId) {
        for (CatalogSnapshot.AutomaticItem automatic : current.automaticItems()) {
            BigDecimal quantity = switch (automatic.trigger()) {
                case "ORDER_OPEN" -> automatic.mode() == null || automatic.mode().equals(order.getMode().name())
                        ? automatic.quantity() : null;
                case "TABLE_OPEN_PER_GUEST" -> order.getTableId() == null || order.getGuests() == null ? null
                        : automatic.quantity().multiply(BigDecimal.valueOf(order.getGuests()));
                default -> null;
            };
            if (quantity == null || CatalogPricing.previewPrice(current, automatic.itemId(), automatic.variantId())
                    .isEmpty()) {
                continue;
            }
            OrderLine line = lines.saveAndFlush(OrderLine.automaticDraft(order.getId(), automatic.itemId(),
                    automatic.variantId(), quantity, employeeId, clock.instant()));
            changed("ORDER_LINE_DRAFTED", order, line);
        }
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
        return views.draftView(order, line, current);
    }

    @Transactional
    public void delete(UUID orderId, UUID lineId, DeleteLineRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.ORDER_EDIT);
        Order order = lookup.inSessionStore(pos, orderId);
        order.requireChangeable();
        OrderLine line = draft(orderId, lineId, request.version());
        components.deleteAll(components.findByLineIdOrderByPosition(lineId));
        components.flush();
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
