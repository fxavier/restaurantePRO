package com.xavier.backend.orders.controller;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.model.dto.AddLineRequest;
import com.xavier.backend.orders.model.dto.DeleteLineRequest;
import com.xavier.backend.orders.model.dto.LineView;
import com.xavier.backend.orders.model.dto.OrderSummary;
import com.xavier.backend.orders.model.dto.UpdateLineRequest;
import com.xavier.backend.orders.service.DraftLineService;
import com.xavier.backend.orders.service.OrderQueryService;
import com.xavier.backend.shared.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.xavier.backend.orders.model.dto.OpenOrderRequest;
import com.xavier.backend.orders.model.dto.OpenTableRequest;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.service.OrderOpeningService;
import com.xavier.backend.shared.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** POS orders and their draft lines (PRD S13). Store and terminal come from the session (D39). */
@RestController
class PosOrderController {

    static final String ORDERS = "/api/v1/pos/orders";

    private final OrderOpeningService opening;
    private final OrderQueryService queries;
    private final DraftLineService drafts;
    private final Idempotent idempotent;

    PosOrderController(OrderOpeningService opening, OrderQueryService queries, DraftLineService drafts,
                       IdempotencyService idempotency, AccessPolicy accessPolicy) {
        this.opening = opening;
        this.queries = queries;
        this.drafts = drafts;
        this.idempotent = new Idempotent(idempotency, accessPolicy);
    }

    @GetMapping(ORDERS)
    PageResponse<OrderSummary> list(@RequestParam(defaultValue = "OPEN") OrderLifecycle lifecycle,
                                    @PageableDefault(size = 50, sort = "openedAt", direction = Sort.Direction.ASC)
                                    Pageable pageable) {
        return queries.list(lifecycle, pageable);
    }

    @GetMapping(ORDERS + "/{id}")
    OrderView get(@PathVariable UUID id) {
        return queries.get(id);
    }

    @PostMapping(ORDERS + "/{id}/lines")
    ResponseEntity<LineView> addLine(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody AddLineRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.line.add", key, request, body, LineView.class, () -> {
            LineView line = drafts.add(id, body);
            return ResponseEntity.created(URI.create(ORDERS + "/" + id + "/lines/" + line.id())).body(line);
        });
    }

    @PutMapping(ORDERS + "/{id}/lines/{lineId}")
    LineView updateLine(@PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody UpdateLineRequest body) {
        return drafts.update(id, lineId, body);
    }

    @DeleteMapping(ORDERS + "/{id}/lines/{lineId}")
    ResponseEntity<Void> deleteLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                    @Valid @RequestBody DeleteLineRequest body) {
        drafts.delete(id, lineId, body);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/pos/tables/{tableId}/open")
    ResponseEntity<OrderView> openTable(@PathVariable UUID tableId, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody OpenTableRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.table.open", key, request, body, OrderView.class,
                () -> created(opening.openTable(tableId, body)));
    }

    @PostMapping(ORDERS)
    ResponseEntity<OrderView> open(@RequestHeader("Idempotency-Key") String key,
                                   @Valid @RequestBody OpenOrderRequest body, HttpServletRequest request) {
        return idempotent.execute("orders.order.open", key, request, body, OrderView.class,
                () -> created(opening.openWalkIn(body)));
    }

    private static ResponseEntity<OrderView> created(OrderView order) {
        return ResponseEntity.created(URI.create(ORDERS + "/" + order.id())).body(order);
    }
}
