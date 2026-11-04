package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashRegister;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.model.dto.ClosingView;
import com.xavier.backend.cash.model.dto.CreateClosingRequest;
import com.xavier.backend.cash.model.dto.Justification;
import com.xavier.backend.cash.model.dto.JustifyClosingRequest;
import com.xavier.backend.cash.model.dto.RejectClosingRequest;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashRegisterRepository;
import com.xavier.backend.cash.repository.CashSessionCountRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash closings (spec §8.3 CashClosing): the operator drafts, justifies and submits at the POS; a manager approves
 * or rejects in the BackOffice. A rejected closing stays as it was, and the next draft is a new attempt.
 */
@Service
public class CashClosingService {

    static final String ENTITY = "CashClosing";
    /** Prometheus: {@code cash_closing_difference_total{store}}. Σ |difference| of approved closings. */
    static final String DIFFERENCE_METRIC = "cash.closing.difference";

    private final CashClosingRepository closings;
    private final CashRegisterRepository registers;
    private final CashDayRepository days;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final CashSessionCountRepository counts;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final CashAudit audit;
    private final MeterRegistry meters;
    private final Clock clock;

    CashClosingService(CashClosingRepository closings, CashRegisterRepository registers, CashDayRepository days,
                       CashSessionRepository sessions, CashMovementRepository movements,
                       CashSessionCountRepository counts, OrganizationApi organization, AccessPolicy accessPolicy,
                       CashAudit audit, MeterRegistry meters, Clock clock) {
        this.closings = closings;
        this.registers = registers;
        this.days = days;
        this.sessions = sessions;
        this.movements = movements;
        this.counts = counts;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * A DRAFT for the register and day, once every session of that register on the day is CLOSED. The day is locked
     * {@code FOR UPDATE}, so no session of the register opens while the closing is drafted (sessions read the day
     * {@code FOR SHARE}); afterwards the live closing itself keeps the register shut for the day.
     */
    @Transactional
    public ClosingView create(CreateClosingRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        CashRegister register = registers.findById(request.registerId())
                .orElseThrow(() -> new CashNotFoundException(CashRegisterService.ENTITY, request.registerId()));
        StoreScope.requireSameStore(pos.storeId(), register.getStoreId(), CashRegisterService.ENTITY,
                register.getId());
        CashDay day = days.findByIdForUpdate(request.cashDayId())
                .orElseThrow(() -> new CashNotFoundException(CashDayService.ENTITY, request.cashDayId()));
        StoreScope.requireSameStore(pos.storeId(), day.getStoreId(), CashDayService.ENTITY, day.getId());
        day.requireNotFinanciallyClosed();
        requireAllSessionsClosed(register, day);
        if (closings.existsByRegisterIdAndCashDayIdAndStatusIn(register.getId(), day.getId(),
                CashClosingStatus.LIVE)) {
            throw liveClosingExists(register);
        }
        Currency currency = organization.companyProfile().currency();
        UUID previous = closings.findFirstByRegisterIdAndCashDayIdOrderByAttemptNoDesc(register.getId(),
                day.getId()).map(CashClosing::getId).orElse(null);
        int attemptNo = previous == null ? 1 : closings.findById(previous).orElseThrow().getAttemptNo() + 1;
        CashClosing closing = UniqueConstraints.saveAndFlush(closings,
                CashClosing.draft(pos.storeId(), register.getId(), day.getId(), attemptNo, previous,
                        CashAmounts.byMethod(movements.expectedByRegisterAndDay(register.getId(), day.getId()),
                                currency),
                        CashAmounts.byMethod(counts.countedByRegisterAndDay(register.getId(), day.getId()),
                                currency),
                        currency, pos.employeeId(), clock.instant()),
                () -> liveClosingExists(register), "cash_closing_live_uk", "cash_closing_attempt_uk");
        record("CASH_CLOSING_DRAFTED", closing, null);
        return closing.toView();
    }

    @Transactional
    public ClosingView justify(UUID id, JustifyClosingRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        CashClosing closing = posClosing(pos, id);
        OptimisticLocking.requireVersion(CashClosing.class, id, closing.getVersion(), request.version());
        Map<String, String> justifications = new LinkedHashMap<>();
        for (Justification justification : request.justifications()) {
            justifications.put(justification.methodCode(), justification.text());
        }
        closing.justify(justifications, clock.instant());
        closings.saveAndFlush(closing);
        record("CASH_CLOSING_JUSTIFIED", closing, null);
        return closing.toView();
    }

    @Transactional
    public ClosingView submit(UUID id) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        CashClosing closing = posClosing(pos, id);
        closing.submit(pos.employeeId(), clock.instant());
        closings.saveAndFlush(closing);
        record("CASH_CLOSING_SUBMITTED", closing, null);
        return closing.toView();
    }

    /** SUBMITTED → APPROVED. Not the day's financial close, which is its own command (spec §8.3). */
    @Transactional
    public ClosingView approve(UUID id) {
        CashClosing closing = backOfficeClosing(id);
        Actor actor = accessPolicy.current();
        closing.approve(actor.userId(), actor.kind(), clock.instant());
        closings.saveAndFlush(closing);
        Counter.builder(DIFFERENCE_METRIC)
                .description("Absolute difference between counted and expected amounts of approved cash closings")
                .tag("store", closing.getStoreId().toString())
                .register(meters)
                .increment(closing.totalAbsoluteDifference().doubleValue());
        record("CASH_CLOSING_APPROVED", closing, null);
        return closing.toView();
    }

    /** SUBMITTED → REJECTED. The closing is kept; the register may then draft its next attempt. */
    @Transactional
    public ClosingView reject(UUID id, RejectClosingRequest request) {
        CashClosing closing = backOfficeClosing(id);
        Actor actor = accessPolicy.current();
        closing.reject(actor.userId(), actor.kind(), request.reason(), clock.instant());
        closings.saveAndFlush(closing);
        record("CASH_CLOSING_REJECTED", closing, request.reason().strip());
        return closing.toView();
    }

    private void requireAllSessionsClosed(CashRegister register, CashDay day) {
        if (sessions.existsByRegisterIdAndCashDayIdAndStatus(register.getId(), day.getId(),
                CashSessionStatus.OPEN)) {
            throw new CashRuleViolationException(CashRule.SESSION_STILL_OPEN,
                    "The cash register " + register.getCode() + " still has an open session.");
        }
        if (!sessions.existsByRegisterIdAndCashDayId(register.getId(), day.getId())) {
            throw new CashRuleViolationException(CashRule.NO_SESSIONS,
                    "The cash register " + register.getCode() + " had no session on " + day.getBusinessDate() + ".");
        }
    }

    private CashClosing posClosing(PosContext pos, UUID id) {
        CashClosing closing = closings.findByIdForUpdate(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
        StoreScope.requireSameStore(pos.storeId(), closing.getStoreId(), ENTITY, id);
        return closing;
    }

    /** D42: read only the store first (404), authorize in it (403), then lock the closing. */
    private CashClosing backOfficeClosing(UUID id) {
        UUID storeId = closings.findStoreIdById(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
        accessPolicy.require(Permission.CASH_CLOSING_APPROVE, storeId);
        return closings.findByIdForUpdate(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
    }

    private static IllegalCashStateException liveClosingExists(CashRegister register) {
        return new IllegalCashStateException(CashRegisterService.ENTITY, register.getId(),
                CashClosingStatus.DRAFT, "draft another closing for this day while one is live");
    }

    private void record(String action, CashClosing closing, String reason) {
        audit.record(action, ENTITY, closing.getId(), closing.getStoreId(), null, reason,
                Map.of("registerId", closing.getRegisterId().toString(), "attemptNo", closing.getAttemptNo(),
                        "status", closing.getStatus().name()));
    }
}
