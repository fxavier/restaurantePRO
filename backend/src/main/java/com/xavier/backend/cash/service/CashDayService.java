package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.model.CashClosing;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashDayReopening;
import com.xavier.backend.cash.model.CashDayStatus;
import com.xavier.backend.cash.model.dto.ReopenDayRequest;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.CashDayReopeningRepository;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.identity.ApprovalDeniedException;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash day use cases outside settlement (spec §8.3 CashDay): the current day at the POS, the financial close and the
 * reopen (P19). The operational close is settlement's ({@code CashApi.closeDayOperationally}, D37).
 */
@Service
public class CashDayService {

    static final String ENTITY = "CashDay";

    private final CashDayRepository days;
    private final CashDayReopeningRepository reopenings;
    private final CashSessionRepository sessions;
    private final CashClosingRepository closings;
    private final OrganizationApi organization;
    private final ApproverVerifier approvers;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashDayService(CashDayRepository days, CashDayReopeningRepository reopenings, CashSessionRepository sessions,
                   CashClosingRepository closings, OrganizationApi organization, ApproverVerifier approvers,
                   AccessPolicy accessPolicy, RowLocks locks, CashAudit audit, Clock clock) {
        this.days = days;
        this.locks = locks;
        this.reopenings = reopenings;
        this.sessions = sessions;
        this.closings = closings;
        this.organization = organization;
        this.approvers = approvers;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The POS store's current cash day: the oldest one still OPEN, else the day of today's business date (the
     * store's cutoff decides it) in whatever state; 404 when neither exists. A day worked past the cutoff stays
     * current until it closes (spec §4: a business day may cross midnight; §8.4: a day with open sessions or orders
     * does not close), even when a morning session has already opened the next one.
     */
    @Transactional(readOnly = true)
    public DayView current() {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_VIEW);
        Optional<CashDay> oldestOpen = days.findFirstByStoreIdAndStatusOrderByBusinessDateAsc(pos.storeId(),
                CashDayStatus.OPEN);
        if (oldestOpen.isPresent()) {
            return oldestOpen.get().toView();
        }
        StoreSummary store = organization.findStore(pos.storeId())
                .orElseThrow(() -> new CashNotFoundException("Store", pos.storeId()));
        LocalDate businessDate = BusinessDays.of(clock.instant(), store.timezone(), store.businessDayCutoff());
        return days.findByStoreIdAndBusinessDate(store.id(), businessDate).map(CashDay::toView)
                .orElseThrow(() -> new CashNotFoundException(ENTITY, store.id() + "/" + businessDate));
    }

    /**
     * OPERATIONALLY_CLOSED → FINANCIALLY_CLOSED, once every register that had a session on the day has an APPROVED
     * closing (422 {@code CLOSINGS_NOT_APPROVED}). Distinct from approving a closing (spec §8.3).
     */
    @Transactional
    public DayView closeFinancially(UUID id) {
        accessPolicy.require(Permission.CASH_CLOSING_APPROVE, storeOf(id));
        CashDay day = locks.dayForUpdate(id);
        if (day.getStatus() == CashDayStatus.OPERATIONALLY_CLOSED) {
            requireEveryRegisterApproved(day);
        }
        Actor actor = accessPolicy.current();
        day.closeFinancially(actor.userId(), actor.kind(), clock.instant());
        days.saveAndFlush(day);
        audit.record("CASH_DAY_FINANCIALLY_CLOSED", ENTITY, id, day.getStoreId(),
                Map.of("businessDate", day.getBusinessDate().toString()));
        return day.toView();
    }

    /**
     * Phase 1 of the reopen (D40), outside any transaction: {@code CASH_DAY_CLOSE} in the day's store, and an
     * approver holding {@code CASH_CLOSING_APPROVE} there. A wrong PIN counts toward the approver's lockout.
     *
     * @throws ApprovalDeniedException (403) for a missing or refused approver
     */
    public Approval authorizeReopen(UUID id, String approverCode, String approverPin) {
        UUID storeId = storeOf(id);
        accessPolicy.require(Permission.CASH_DAY_CLOSE, storeId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        return new Approval(id, approvers.verify(approverCode, approverPin, Permission.CASH_CLOSING_APPROVE,
                storeId));
    }

    /** Phase 2: OPERATIONALLY_CLOSED → OPEN, recorded as a reopening fact that keeps the undone close (P19). */
    @Transactional
    public DayView reopen(UUID id, ReopenDayRequest request, Approval approval) {
        if (!approval.dayId().equals(id)) {
            throw new IllegalArgumentException("The approval is for day " + approval.dayId() + ", not " + id);
        }
        accessPolicy.require(Permission.CASH_DAY_CLOSE, storeOf(id));
        CashDay day = locks.dayForUpdate(id);
        Actor actor = accessPolicy.current();
        CashDayReopening reopening = day.reopen(actor.userId(), actor.kind(), approval.approverEmployeeId(),
                request.reason(), clock.instant());
        reopenings.save(reopening);
        days.saveAndFlush(day);
        audit.record("CASH_DAY_REOPENED", ENTITY, id, day.getStoreId(), approval.approverEmployeeId(),
                request.reason().strip(), Map.of("businessDate", day.getBusinessDate().toString()));
        return day.toView();
    }

    private void requireEveryRegisterApproved(CashDay day) {
        Set<UUID> worked = new HashSet<>(sessions.findRegisterIdsByCashDayId(day.getId()));
        Set<UUID> approved = closings.findByCashDayIdAndStatus(day.getId(), CashClosingStatus.APPROVED).stream()
                .map(CashClosing::getRegisterId).collect(Collectors.toSet());
        worked.removeAll(approved);
        if (!worked.isEmpty()) {
            throw new CashRuleViolationException(CashRule.CLOSINGS_NOT_APPROVED,
                    worked.size() + " cash register(s) of " + day.getBusinessDate() + " have no approved closing: "
                            + List.copyOf(worked) + ".");
        }
    }

    private UUID storeOf(UUID id) {
        return storeOf(days, id);
    }

    /** D42: the day's store only, 404 if the day does not exist (also behind {@code CashApi.storeOfDay}). */
    static UUID storeOf(CashDayRepository days, UUID id) {
        return days.findStoreIdById(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
    }
}
