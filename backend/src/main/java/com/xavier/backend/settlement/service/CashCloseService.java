package com.xavier.backend.settlement.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.PosContext;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.DayView;
import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementNotFoundException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.AttemptStatus;
import com.xavier.backend.settlement.model.dto.CloseDayRequest;
import com.xavier.backend.settlement.model.dto.CloseSessionRequest;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cash closes settlement coordinates (D37, D50 §7.8, spec §8.4): a session closes only with nothing in doubt
 * under its responsibility, and a day closes operationally only with no open session, attempt in doubt, fiscal draft
 * or open order in its scope. Cash records the facts; the actor passed to it is always the current one.
 */
@Service
public class CashCloseService {

    private static final Logger log = LoggerFactory.getLogger(CashCloseService.class);
    static final String SESSION = "CashSession";
    static final String DAY = "CashDay";

    private final AccessPolicy accessPolicy;
    private final CashApi cash;
    private final OrdersApi orders;
    private final FiscalApi fiscal;
    private final OrganizationApi organization;
    private final PaymentAttemptRepository attempts;

    CashCloseService(AccessPolicy accessPolicy, CashApi cash, OrdersApi orders, FiscalApi fiscal,
                     OrganizationApi organization, PaymentAttemptRepository attempts) {
        this.accessPolicy = accessPolicy;
        this.cash = cash;
        this.orders = orders;
        this.fiscal = fiscal;
        this.organization = organization;
        this.attempts = attempts;
    }

    /**
     * The operator's own session, locked {@code FOR UPDATE} (an attempt being created on it waits, then is refused),
     * with no attempt CREATED, PENDING or UNKNOWN on it, closed with its blind count.
     *
     * @throws SettlementConflictException 409 {@code PAYMENT_IN_DOUBT}
     * @throws AccessDeniedException 403 for a session of another store or another operator
     */
    @Transactional
    public SessionView closeSession(UUID sessionId, CloseSessionRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_OPERATE);
        SessionView session = cash.lockSession(sessionId);
        accessPolicy.requireSessionStore(pos, session.storeId(), SESSION, sessionId);
        if (!session.operatorEmployeeId().equals(pos.employeeId())) {
            log.warn("Access denied: session {} belongs to employee {}, not {}", sessionId,
                    session.operatorEmployeeId(), pos.employeeId());
            throw new AccessDeniedException("Cash session " + sessionId + " is not yours");
        }
        attempts.findFirstBySessionIdAndStatusIn(sessionId, AttemptStatus.OPEN).ifPresent(open -> {
            throw SettlementConflictException.inDoubt("cash session", sessionId, open.getId());
        });
        Currency currency = organization.findStore(session.storeId()).map(StoreSummary::currency).orElseThrow();
        List<MethodCount> counts = request.counts().stream().map(count -> new MethodCount(count.methodCode(),
                PaymentAttemptService.money(count.amount(), currency))).toList();
        cash.closeSession(sessionId, request.version(), counts, pos.employeeId());
        return cash.lockSession(sessionId);
    }

    /**
     * OPEN → OPERATIONALLY_CLOSED under the day's lock (D50 §7.8).
     *
     * @throws SettlementRuleException 422 {@code OPEN_SESSIONS}, {@code PAYMENT_IN_DOUBT}, {@code FISCAL_PENDING},
     *         {@code OPEN_ORDERS}
     */
    @Transactional
    public DayView closeDayOperationally(UUID dayId, CloseDayRequest request) {
        PosContext pos = accessPolicy.requirePos(Permission.CASH_DAY_CLOSE);
        DayView day = cash.findDay(dayId)
                .orElseThrow(() -> new SettlementNotFoundException(DAY, dayId));
        accessPolicy.requireSessionStore(pos, day.storeId(), DAY, dayId);
        cash.lockDay(day.storeId(), day.businessDate());
        UUID storeId = day.storeId();
        if (!cash.openSessionIds(dayId).isEmpty()) {
            throw refused(SettlementRule.OPEN_SESSIONS, "a cash session of the day is still open");
        }
        if (attempts.findFirstByStoreIdAndStatusIn(storeId, AttemptStatus.OPEN).isPresent()) {
            throw refused(SettlementRule.PAYMENT_IN_DOUBT, "a payment attempt is still in doubt");
        }
        if (fiscal.countDrafts(storeId) > 0) {
            throw refused(SettlementRule.FISCAL_PENDING, "a fiscal document is still pending");
        }
        if (orders.countOpen(storeId, day.businessDate()) > 0) {
            throw refused(SettlementRule.OPEN_ORDERS, "an order of the day is still open");
        }
        Actor actor = accessPolicy.current();
        cash.closeDayOperationally(dayId, request.version(), actor.userId(), actor.kind());
        return cash.findDay(dayId).orElseThrow();
    }

    private static SettlementRuleException refused(SettlementRule rule, String why) {
        return new SettlementRuleException(rule, "The cash day cannot close operationally: " + why + ".");
    }
}
