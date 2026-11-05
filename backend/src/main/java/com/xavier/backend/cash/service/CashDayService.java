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
import com.xavier.backend.cash.model.dto.CurrentDayView;
import com.xavier.backend.cash.model.dto.ReopenDayRequest;
import com.xavier.backend.cash.repository.CashMovementRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash day use cases outside settlement (spec §8.3 CashDay): the current day at the POS, the financial close and the
 * reopen (P19, D79 (3)). The operational close is settlement's ({@code CashApi.closeDayOperationally}, D37).
 */
@Service
public class CashDayService {

    static final String ENTITY = "CashDay";

    private final CashDayRepository days;
    private final CashDayReopeningRepository reopenings;
    private final CashSessionRepository sessions;
    private final CashMovementRepository movements;
    private final ClosingRounds rounds;
    private final OrganizationApi organization;
    private final ApproverVerifier approvers;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashDayService(CashDayRepository days, CashDayReopeningRepository reopenings, CashSessionRepository sessions,
                   CashMovementRepository movements, ClosingRounds rounds, OrganizationApi organization, ApproverVerifier approvers,
                   AccessPolicy accessPolicy, RowLocks locks, CashAudit audit, Clock clock) {
        this.days = days;
        this.locks = locks;
        this.reopenings = reopenings;
        this.sessions = sessions;
        this.movements = movements;
        this.rounds = rounds;
        this.organization = organization;
        this.approvers = approvers;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The POS store's current cash day (P28): the day of today's business date (the store's cutoff decides it), in
     * whatever state; until that day has opened, the oldest OPEN day, so the POS is not stuck after the cutoff; 404
     * when neither exists. {@code earlierOpenDays} are the OPEN days before it, which take corrections only and must
     * be closed (spec §4 "dia comercial pode atravessar a meia-noite", §8.4).
     */
    @Transactional(readOnly = true)
    public CurrentDayView current() {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_VIEW);
        StoreSummary store = organization.findStore(pos.storeId())
                .orElseThrow(() -> new CashNotFoundException("Store", pos.storeId()));
        LocalDate businessDate = BusinessDays.of(clock.instant(), store.timezone(), store.businessDayCutoff());
        CashDay current = days.findByStoreIdAndBusinessDate(store.id(), businessDate)
                .or(() -> days.findFirstByStoreIdAndStatusOrderByBusinessDateAsc(store.id(), CashDayStatus.OPEN))
                .orElseThrow(() -> new CashNotFoundException(ENTITY, store.id() + "/" + businessDate));
        List<CurrentDayView.OpenDay> earlier = days.findByStoreIdAndStatusAndBusinessDateBeforeOrderByBusinessDateAsc(
                        store.id(), CashDayStatus.OPEN, current.getBusinessDate()).stream()
                .map(day -> new CurrentDayView.OpenDay(day.getId(), day.getBusinessDate())).toList();
        return CurrentDayView.of(current.toView(), earlier);
    }

    /**
     * OPERATIONALLY_CLOSED → FINANCIALLY_CLOSED, once every register that had a session on the day has a current
     * APPROVED closing ({@link #requireEveryRegisterApproved}), under the day's lock. Distinct from approving a
     * closing (spec §8.3).
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
     * Phase 1 of the reopen (D40), outside any transaction: in the day's store, {@code CASH_DAY_CLOSE} to reopen an
     * OPERATIONALLY_CLOSED day (P19) or {@code CASH_DAY_REOPEN} for a FINANCIALLY_CLOSED one (D79 (3)), and an
     * approver holding {@code CASH_CLOSING_APPROVE} there. A wrong PIN counts toward the approver's lockout.
     *
     * @throws ApprovalDeniedException (403) for a missing or refused approver
     */
    public Approval authorizeReopen(UUID id, String approverCode, String approverPin) {
        UUID storeId = storeOf(id);
        accessPolicy.require(reopenPermission(days.findById(id).orElseThrow().getStatus()), storeId);
        if (approverCode == null || approverCode.isBlank() || approverPin == null || approverPin.isBlank()) {
            throw new ApprovalDeniedException();
        }
        return new Approval(id, approvers.verify(approverCode, approverPin, Permission.CASH_CLOSING_APPROVE,
                storeId));
    }

    /**
     * Phase 2: back to OPEN, recorded as a reopening fact that keeps the undone close (P19) and the state it came
     * from. The permission is checked again against the locked day's state, which may have moved since phase 1.
     * Later closings of the day get the next round when it came from FINANCIALLY_CLOSED (D79 (3)).
     */
    @Transactional
    public DayView reopen(UUID id, ReopenDayRequest request, Approval approval) {
        if (!approval.dayId().equals(id)) {
            throw new IllegalArgumentException("The approval is for day " + approval.dayId() + ", not " + id);
        }
        UUID storeId = storeOf(id);
        CashDay day = locks.dayForUpdate(id);
        accessPolicy.require(reopenPermission(day.getStatus()), storeId);
        Actor actor = accessPolicy.current();
        CashDayReopening reopening = day.reopen(actor.userId(), actor.kind(), approval.approverEmployeeId(),
                request.reason(), clock.instant());
        reopenings.save(reopening);
        days.saveAndFlush(day);
        audit.record("CASH_DAY_REOPENED", ENTITY, id, day.getStoreId(), approval.approverEmployeeId(),
                request.reason().strip(), Map.of("businessDate", day.getBusinessDate().toString(),
                        "fromStatus", reopening.getFromStatus().name()));
        return day.toView();
    }

    /** An OPEN day is refused by the state machine (409) after the R2 permission check. */
    private static Permission reopenPermission(CashDayStatus status) {
        return status == CashDayStatus.FINANCIALLY_CLOSED ? Permission.CASH_DAY_REOPEN : Permission.CASH_DAY_CLOSE;
    }

    /**
     * D79: per register that worked on the day, its latest closing that was not rejected (latest round, then latest
     * attempt) is APPROVED (else 422 {@code CLOSINGS_NOT_APPROVED}) and still compares what the register should hold
     * now (else 409 {@code CLOSING_STALE}: an adjustment after a financial reopen needs a closing of the new round).
     */
    private void requireEveryRegisterApproved(CashDay day) {
        List<UUID> unapproved = new ArrayList<>();
        for (UUID register : new TreeSet<>(sessions.findRegisterIdsByCashDayId(day.getId()))) {
            Optional<CashClosing> latest = rounds.latestLive(register, day.getId());
            if (latest.isEmpty() || latest.get().getStatus() != CashClosingStatus.APPROVED) {
                unapproved.add(register);
                continue;
            }
            latest.get().requireCurrent(CashAmounts.byMethod(movements.expectedByRegisterAndDay(register,
                    day.getId()), latest.get().currency()));
        }
        if (!unapproved.isEmpty()) {
            throw new CashRuleViolationException(CashRule.CLOSINGS_NOT_APPROVED,
                    unapproved.size() + " cash register(s) of " + day.getBusinessDate()
                            + " have no approved closing: " + unapproved + ".");
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
