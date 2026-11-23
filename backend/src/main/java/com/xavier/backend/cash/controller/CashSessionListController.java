package com.xavier.backend.cash.controller;

import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.model.dto.AdjustmentRow;
import com.xavier.backend.cash.model.dto.SessionRow;
import com.xavier.backend.cash.service.CashSessionQueryService;
import com.xavier.backend.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.List;
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

/** The BackOffice reads of a store's cash sessions (Financeiro): the list, newest first, and a session's adjustments. */
@RestController
class CashSessionListController {

    private final CashSessionQueryService sessions;

    CashSessionListController(CashSessionQueryService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/api/v1/backoffice/cash/sessions")
    PageResponse<SessionRow> list(@RequestParam UUID storeId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) CashSessionStatus status,
                                  @PageableDefault(sort = "openedAt", direction = Sort.Direction.DESC)
                                  @ParameterObject Pageable pageable) {
        return sessions.list(storeId, status, from, to, pageable);
    }

    /** The session detail's corrections, newest first; a session has a handful, so the list is not paged. */
    @GetMapping("/api/v1/backoffice/cash/sessions/{id}/adjustments")
    List<AdjustmentRow> adjustments(@PathVariable UUID id) {
        return sessions.adjustments(id);
    }
}
