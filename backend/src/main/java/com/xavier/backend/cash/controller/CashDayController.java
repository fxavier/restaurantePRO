package com.xavier.backend.cash.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.model.dto.ReopenDayRequest;
import com.xavier.backend.cash.service.Approval;
import com.xavier.backend.cash.service.CashDayService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The operational close lives in settlement (D37): {@code POST /api/v1/pos/cash/days/{id}/operational-close}. */
@RestController
class CashDayController {

    static final String BACKOFFICE = "/api/v1/backoffice/cash/days";
    private static final Map<String, Object> NO_BODY = Map.of();

    private final CashDayService days;
    private final IdempotentRequests idempotent;

    CashDayController(CashDayService days, IdempotentRequests idempotent) {
        this.days = days;
        this.idempotent = idempotent;
    }

    @GetMapping("/api/v1/pos/cash/days/current")
    DayView current() {
        return days.current();
    }

    @PostMapping(BACKOFFICE + "/{id}/financial-close")
    ResponseEntity<DayView> closeFinancially(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             HttpServletRequest request) {
        return idempotent.execute("cash.day.financial-close", key, request, NO_BODY, DayView.class,
                () -> ResponseEntity.ok(days.closeFinancially(id)));
    }

    /** D40: the approver is verified first, outside any transaction; the headers never enter the idempotency hash. */
    @PostMapping(BACKOFFICE + "/{id}/reopen")
    ResponseEntity<DayView> reopen(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                   @RequestHeader(value = "X-Approver-Code", required = false) String approverCode,
                                   @RequestHeader(value = "X-Approver-Pin", required = false) String approverPin,
                                   @Valid @RequestBody ReopenDayRequest body, HttpServletRequest request) {
        Approval approval = days.authorizeReopen(id, approverCode, approverPin);
        return idempotent.execute("cash.day.reopen", key, request, body, DayView.class,
                () -> ResponseEntity.ok(days.reopen(id, body, approval)));
    }
}
