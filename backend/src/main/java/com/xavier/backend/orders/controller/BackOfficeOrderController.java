package com.xavier.backend.orders.controller;

import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.model.dto.OrderRow;
import com.xavier.backend.orders.model.dto.OrderView;
import com.xavier.backend.orders.service.BackOfficeOrderQueryService;
import com.xavier.backend.shared.web.PageResponse;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The BackOffice order search and detail, newest first; {@code status} is the order's lifecycle. {@code q} is at most
 * a table code long (20; an order number has at most 18 digits): a longer text matches nothing, so it is a 400.
 */
@RestController
class BackOfficeOrderController {

    static final String ORDERS = "/api/v1/backoffice/orders";

    private final BackOfficeOrderQueryService queries;

    BackOfficeOrderController(BackOfficeOrderQueryService queries) {
        this.queries = queries;
    }

    @GetMapping(ORDERS)
    PageResponse<OrderRow> search(@RequestParam UUID storeId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) OrderLifecycle status,
                                  @RequestParam(required = false) OrderMode mode,
                                  @RequestParam(required = false) @Size(max = 20) String q,
                                  @PageableDefault(size = 50, sort = "openedAt", direction = Sort.Direction.DESC)
                                  @ParameterObject Pageable pageable) {
        return queries.search(storeId, from, to, status, mode, q, pageable);
    }

    @GetMapping(ORDERS + "/{id}")
    OrderView get(@PathVariable UUID id) {
        return queries.get(id);
    }
}
