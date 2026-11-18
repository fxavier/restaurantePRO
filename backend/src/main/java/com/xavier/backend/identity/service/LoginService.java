package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The description of the current caller ({@code GET /auth/session}). BackOffice login, its second factor and its
 * sessions are Keycloak's (D175, D176); the POS PIN login is {@link PosLoginService}.
 */
@Service
public class LoginService {

    private final UserAccountRepository accounts;
    private final EmployeeRepository employees;
    private final AccessPolicy accessPolicy;
    private final SessionTenant sessionTenant;

    LoginService(UserAccountRepository accounts, EmployeeRepository employees, AccessPolicy accessPolicy,
                 SessionTenant sessionTenant) {
        this.accounts = accounts;
        this.employees = employees;
        this.accessPolicy = accessPolicy;
        this.sessionTenant = sessionTenant;
    }

    @Transactional(readOnly = true)
    public SessionView currentSession() {
        return sessionTenant.stamp(describe(accessPolicy.current()));
    }

    private SessionView describe(Actor actor) {
        if (actor.kind() == ActorKind.EMPLOYEE) {
            return employees.findById(actor.employeeId())
                    .map(employee -> SessionView.employee(actor, employee.getName(), employee.isMustChangePin()))
                    .orElseGet(() -> SessionView.employee(actor, null, false));
        }
        return accounts.findById(actor.userId())
                .map(account -> SessionView.account(actor, account.getUsername()))
                .orElseGet(() -> SessionView.account(actor, null));
    }
}
