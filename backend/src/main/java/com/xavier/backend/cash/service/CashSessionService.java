package com.xavier.backend.cash.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.exception.CashNotFoundException;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.CashSessionAlreadyOpenException;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.model.CashDay;
import com.xavier.backend.cash.model.CashDayStatus;
import com.xavier.backend.cash.model.CashMovement;
import com.xavier.backend.cash.model.CashRegister;
import com.xavier.backend.cash.model.CashSession;
import com.xavier.backend.cash.model.CashSessionStatus;
import com.xavier.backend.cash.model.dto.MethodAmount;
import com.xavier.backend.cash.model.dto.OpenSessionRequest;
import com.xavier.backend.cash.model.dto.SessionDetails;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.CashDayReopeningRepository;
import com.xavier.backend.cash.repository.CashDayRepository;
import com.xavier.backend.cash.repository.CashMovementRepository;
import com.xavier.backend.cash.repository.CashRegisterRepository;
import com.xavier.backend.cash.repository.CashSessionRepository;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opening a cash session and reading sessions at the POS (spec §4 "sessões diretas", P16). */
@Service
public class CashSessionService {

    static final String ENTITY = "CashSession";

    private final CashStoreProbe stores;
    private final CashSessionRepository sessions;
    private final CashRegisterRepository registers;
    private final CashDayRepository days;
    private final CashMovementRepository movements;
    private final CashClosingRepository closings;
    private final CashDayReopeningRepository reopenings;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final RowLocks locks;
    private final CashAudit audit;
    private final Clock clock;

    CashSessionService(CashStoreProbe stores, CashSessionRepository sessions, CashRegisterRepository registers, CashDayRepository days,
                       CashMovementRepository movements, CashClosingRepository closings,
                       CashDayReopeningRepository reopenings, OrganizationApi organization, AccessPolicy accessPolicy, RowLocks locks, CashAudit audit,
                       Clock clock) {
        this.stores = stores;
        this.sessions = sessions;
        this.locks = locks;
        this.registers = registers;
        this.days = days;
        this.movements = movements;
        this.closings = closings;
        this.reopenings = reopenings;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Opens the operator's session on a register of the POS store, in the business day of now by the store's cutoff,
     * which is created if this is its first session (P28: even while an earlier day is still OPEN, which the response
     * warns about). The day is read {@code FOR SHARE}, so an operational close or a reopen in progress is waited for
     * and then decides. An earlier day than one already opened, or a reopened day, takes corrections only (P25):
     * 422 {@code DAY_CLOSING_ONLY}.
     */
    @Transactional
    public SessionDetails open(OpenSessionRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        StoreScope.requireSameStore(pos.storeId(), stores.register(request.registerId())
                        .orElseThrow(() -> new CashNotFoundException(CashRegisterService.ENTITY, request.registerId())),
                CashRegisterService.ENTITY, request.registerId());
        CashRegister register = registers.findById(request.registerId()).orElseThrow();
        if (!register.isActive()) {
            throw new CashRuleViolationException(CashRule.REGISTER_INACTIVE,
                    "The cash register " + register.getCode() + " is inactive.");
        }
        StoreSummary store = store(pos.storeId());
        Money openingFloat = CashAmounts.of(request.openingFloat(), store.currency());
        Instant now = clock.instant();
        CashDay day = currentDayForShare(store, now);
        day.requireOpen();
        if (reopenings.existsByCashDayId(day.getId())) {
            throw closingOnly(day, "was reopened");
        }
        if (closings.existsByRegisterIdAndCashDayIdAndStatusIn(register.getId(), day.getId(),
                CashClosingStatus.LIVE)) {
            throw new CashRuleViolationException(CashRule.REGISTER_CLOSED_FOR_DAY,
                    "The cash register " + register.getCode() + " is already being closed for " +
                            day.getBusinessDate() + ".");
        }
        if (sessions.existsByRegisterIdAndStatus(register.getId(), CashSessionStatus.OPEN)
                || sessions.findByStoreIdAndOperatorEmployeeIdAndStatus(pos.storeId(), pos.employeeId(),
                CashSessionStatus.OPEN).isPresent()) {
            throw new CashSessionAlreadyOpenException();
        }
        CashSession session = UniqueConstraints.saveAndFlush(sessions,
                CashSession.open(pos.storeId(), register.getId(), day.getId(), pos.employeeId(), pos.terminalId(),
                        openingFloat, now),
                CashSessionAlreadyOpenException::new,
                "cash_session_register_open_uk", "cash_session_operator_open_uk");
        if (openingFloat.amount().signum() > 0) {
            movements.save(CashMovement.openingFloat(session, now));
        }
        audit.record("CASH_SESSION_OPENED", ENTITY, session.getId(), session.getStoreId(),
                Map.of("registerId", register.getId().toString(), "businessDate", day.getBusinessDate().toString(),
                        "openingFloat", openingFloat.amount().toPlainString()));
        List<String> warnings = days.existsByStoreIdAndStatusAndBusinessDateBefore(store.id(), CashDayStatus.OPEN,
                day.getBusinessDate()) ? List.of(SessionDetails.PREVIOUS_DAY_OPEN) : List.of();
        return details(session, day.getBusinessDate(), warnings);
    }

    /** The POS operator's own OPEN session. */
    @Transactional(readOnly = true)
    public SessionDetails current() {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        CashSession session = sessions.findByStoreIdAndOperatorEmployeeIdAndStatus(pos.storeId(), pos.employeeId(),
                        CashSessionStatus.OPEN)
                .orElseThrow(() -> new CashNotFoundException("Open " + ENTITY + " of employee", pos.employeeId()));
        return details(session);
    }

    @Transactional(readOnly = true)
    public SessionDetails get(UUID id) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_VIEW);
        StoreScope.requireSameStore(pos.storeId(),
                stores.session(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id)), ENTITY, id);
        CashSession session = sessions.findById(id).orElseThrow(() -> new CashNotFoundException(ENTITY, id));
        return details(session);
    }

    /**
     * The store's cash day for {@code now} (its cutoff may put it on yesterday's date), read {@code FOR SHARE}; never
     * one earlier than a day already opened (e.g. after the cutoff moved later).
     */
    private CashDay currentDayForShare(StoreSummary store, Instant now) {
        LocalDate businessDate = BusinessDays.of(now, store.timezone(), store.businessDayCutoff());
        days.findFirstByStoreIdAndBusinessDateAfter(store.id(), businessDate).ifPresent(later -> {
            throw closingOnly(businessDate, "is earlier than " + later.getBusinessDate() + ", which is open");
        });
        days.createIfAbsent(store.id(), businessDate, now);
        return locks.lock(days.findByStoreIdAndBusinessDate(store.id(), businessDate).orElseThrow(),
                LockModeType.PESSIMISTIC_READ);
    }

    private static CashRuleViolationException closingOnly(CashDay day, String why) {
        return closingOnly(day.getBusinessDate(), why);
    }

    private static CashRuleViolationException closingOnly(LocalDate businessDate, String why) {
        return new CashRuleViolationException(CashRule.DAY_CLOSING_ONLY,
                "The cash day " + businessDate + " " + why + ": it takes corrections only, not new sessions.");
    }

    private SessionDetails details(CashSession session) {
        return details(session, days.findById(session.getCashDayId()).orElseThrow().getBusinessDate(), List.of());
    }

    private SessionDetails details(CashSession session, LocalDate businessDate, List<String> warnings) {
        List<MethodAmount> expected = CashAmounts.byMethod(movements.expectedBySession(session.getId()),
                        session.currency()).entrySet().stream()
                .map(e -> new MethodAmount(e.getKey(), e.getValue())).toList();
        return new SessionDetails(session.getId(), session.getStoreId(), session.getRegisterId(),
                session.getCashDayId(), businessDate, session.getOperatorEmployeeId(), session.getStatus().name(),
                session.getOpeningFloat(), session.getOpenedAt(), session.getClosedAt(), session.getVersion(),
                expected, warnings);
    }

    private StoreSummary store(UUID storeId) {
        return organization.findStore(storeId).orElseThrow(() -> new CashNotFoundException("Store", storeId));
    }
}
