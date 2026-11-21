package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashRegister;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.CashSessionCount;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.model.dto.MethodAmount;
import com.xavier.backend.cash.model.dto.SessionRow;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashRegisterRepository;
import com.xavier.backend.cash.repository.CashSessionCountRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.shared.BusinessDateRange;
import com.xavier.backend.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The BackOffice list of a store's cash sessions (Financeiro; CASH_VIEW in that store, D42). */
@Service
public class CashSessionQueryService {

    private final CashSessionRepository sessions;
    private final CashDayRepository days;
    private final CashRegisterRepository registers;
    private final CashSessionCountRepository counts;
    private final AccessPolicy accessPolicy;

    CashSessionQueryService(CashSessionRepository sessions, CashDayRepository days, CashRegisterRepository registers,
                            CashSessionCountRepository counts, AccessPolicy accessPolicy) {
        this.sessions = sessions;
        this.days = days;
        this.registers = registers;
        this.counts = counts;
        this.accessPolicy = accessPolicy;
    }

    /** The store's sessions, optionally in {@code status} and of business dates in {@code [from, to]}. */
    @Transactional(readOnly = true)
    public PageResponse<SessionRow> list(UUID storeId, CashSessionStatus status, LocalDate from, LocalDate to,
                                         Pageable pageable) {
        accessPolicy.require(Permission.CASH_VIEW, storeId);
        BusinessDateRange dates = BusinessDateRange.of(from, to);
        Page<CashSession> page = sessions.search(storeId, status, dates.from(), dates.to(), pageable);
        List<CashSession> content = page.getContent();
        // one read each of days, registers and counts for the whole page, not one per session
        Map<UUID, LocalDate> businessDates = days.findAllById(ids(content, CashSession::getCashDayId)).stream()
                .collect(Collectors.toMap(CashDay::getId, CashDay::getBusinessDate));
        Map<UUID, String> registerCodes = registers.findAllById(ids(content, CashSession::getRegisterId)).stream()
                .collect(Collectors.toMap(CashRegister::getId, CashRegister::getCode));
        Map<UUID, List<MethodAmount>> counted = counts.findBySessionIdInOrderByMethodCode(ids(content,
                        CashSession::getId)).stream()
                .collect(Collectors.groupingBy(CashSessionCount::getSessionId, Collectors.mapping(
                        count -> new MethodAmount(count.getMethodCode(), count.getAmount()), Collectors.toList())));
        return PageResponse.from(page.map(session -> new SessionRow(session.getId(), session.getStoreId(),
                session.getRegisterId(), registerCodes.get(session.getRegisterId()), session.getCashDayId(),
                businessDates.get(session.getCashDayId()), session.getOperatorEmployeeId(),
                session.getStatus().name(), session.getOpeningFloat(), session.getOpenedAt(), session.getClosedAt(),
                session.getClosedByEmployeeId(),
                session.getCloseKind() == null ? null : session.getCloseKind().name(),
                counted.getOrDefault(session.getId(), List.of()))));
    }

    private static Set<UUID> ids(List<CashSession> sessions, Function<CashSession, UUID> id) {
        return sessions.stream().map(id).collect(Collectors.toSet());
    }
}
