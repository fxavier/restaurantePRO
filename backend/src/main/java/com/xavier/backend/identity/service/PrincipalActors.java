package com.xavier.backend.identity.service;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorDirectory;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.model.SupportGrant;
import com.xavier.backend.identity.model.TerminalSession;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.EmployeeStoreAssignmentRepository;
import com.xavier.backend.identity.repository.SupportGrantRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.TerminalSummary;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@link Actor} of a resolved principal, from this tenant's rows only (D177 step 7): called inside the resolved
 * tenant's binding, so row-level security applies. Built again on every request, so a role change, a deactivation or
 * a removed assignment holds from the next request with no session to end.
 */
@Service
class PrincipalActors implements ActorDirectory {

    private static final Logger log = LoggerFactory.getLogger(PrincipalActors.class);

    private final UserAccountRepository accounts;
    private final SupportGrantRepository grants;
    private final EmployeeRepository employees;
    private final EmployeeStoreAssignmentRepository assignments;
    private final AuthorityResolver authorities;
    private final TerminalSessionService posSessions;
    private final OrganizationApi organization;
    private final AuditLog auditLog;
    private final Clock clock;

    PrincipalActors(UserAccountRepository accounts, SupportGrantRepository grants, EmployeeRepository employees,
                    EmployeeStoreAssignmentRepository assignments, AuthorityResolver authorities,
                    TerminalSessionService posSessions, OrganizationApi organization, AuditLog auditLog,
                    Clock clock) {
        this.accounts = accounts;
        this.grants = grants;
        this.employees = employees;
        this.assignments = assignments;
        this.authorities = authorities;
        this.posSessions = posSessions;
        this.organization = organization;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** An active, ordinary (not support) account. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Actor> account(UUID accountId) {
        return accounts.findById(accountId).filter(UserAccount::isActive).filter(account -> !account.isSupport())
                .map(account -> Actor.account(account.getId(), authorities.ofAccount(account)));
    }

    /**
     * The grant's support account while the grant is usable; support's first action on it is audited
     * ({@code SUPPORT_SESSION_STARTED}) and shown to the tenant's administrators ({@code SupportAccessAlerts}).
     */
    @Override
    @Transactional
    public Optional<Actor> support(UUID grantId) {
        Instant now = clock.instant();
        Optional<SupportGrant> grant = grants.findById(grantId).filter(found -> found.isUsableAt(now));
        if (grant.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserAccount> account = accounts.findById(grant.get().getAccountId())
                .filter(UserAccount::isActive).filter(UserAccount::isSupport);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        Actor actor = Actor.account(account.get().getId(), authorities.ofAccount(account.get()));
        if (grant.get().markUsed(now)) {
            grants.saveAndFlush(grant.get());
            log.info("Support access started: grant={} account={}", grantId, actor.userId());
            auditLog.recordAs(actor, "SUPPORT_SESSION_STARTED", "SupportGrant", grantId.toString(), null, null,
                    Map.of("accountId", actor.userId()));
        }
        return Optional.of(actor);
    }

    /**
     * A live POS session, opened on {@code terminalId}, of an active employee still assigned to the session's store, on
     * a terminal that is still enrolled; while the employee must change a manager-set PIN it holds no authority.
     */
    @Override
    @Transactional
    public Optional<Actor> posSession(String token, UUID terminalId) {
        Optional<TerminalSession> session = posSessions.use(token, terminalId);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        TerminalSession live = session.get();
        Optional<Employee> employee = employees.findById(live.getEmployeeId()).filter(Employee::canLogIn);
        boolean terminalEnrolled = organization.findTerminal(live.getTerminalId()).map(TerminalSummary::active)
                .orElse(false);
        if (employee.isEmpty() || !terminalEnrolled
                || assignments.findByEmployeeIdAndStoreId(live.getEmployeeId(), live.getStoreId()).isEmpty()) {
            return Optional.empty();
        }
        MustChangePinFlag.record(employee.get().isMustChangePin());
        Set<String> granted = employee.get().isMustChangePin() ? Set.of()
                : authorities.ofEmployeeInStore(live.getEmployeeId(), live.getStoreId());
        return Optional.of(Actor.employee(live.getEmployeeId(), live.getTerminalId(), live.getStoreId(), granted));
    }
}
