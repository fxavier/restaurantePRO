package com.xavier.backend.settlement.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.settlement.model.dto.CloseDayRequest;
import com.xavier.backend.settlement.model.dto.CloseSessionRequest;
import com.xavier.backend.settlement.model.dto.ForceCloseSessionRequest;
import com.xavier.backend.settlement.service.CashCloseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The cash closes that settlement owns (D37); their routes stay where §9 puts them. */
@RestController
class CashCloseController {

    private final CashCloseService closes;
    private final IdempotentRequests idempotent;

    CashCloseController(CashCloseService closes, IdempotentRequests idempotent) {
        this.closes = closes;
        this.idempotent = idempotent;
    }

    @PostMapping("/api/v1/pos/cash/sessions/{id}/close")
    ResponseEntity<SessionView> closeSession(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody CloseSessionRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("settlement.cash.session.close", key, request, body, SessionView.class,
                () -> ResponseEntity.ok(closes.closeSession(id, body)));
    }

    /** P22: a manager closes a session its operator abandoned (count and reason; 409 {@code PAYMENT_IN_DOUBT}). */
    @PostMapping("/api/v1/pos/cash/sessions/{id}/force-close")
    ResponseEntity<SessionView> forceCloseSession(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody ForceCloseSessionRequest body,
                                                  HttpServletRequest request) {
        return idempotent.execute("settlement.cash.session.force-close", key, request, body, SessionView.class,
                () -> ResponseEntity.ok(closes.forceCloseSession(id, body)));
    }

    @PostMapping("/api/v1/pos/cash/days/{id}/operational-close")
    ResponseEntity<DayView> closeDay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CloseDayRequest body, HttpServletRequest request) {
        return idempotent.execute("settlement.cash.day.operational-close", key, request, body, DayView.class,
                () -> ResponseEntity.ok(closes.closeDayOperationally(id, body)));
    }
}
