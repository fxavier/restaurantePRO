package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.model.Employee;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BackOffice login (username + password, D26) and the description of the current session. Every refusal is the
 * same {@link InvalidCredentialsException} (401); the reason goes to a WARN log line, never the password.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final UserAccountRepository accounts;
    private final EmployeeRepository employees;
    private final AuthorityResolver authorities;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final LoginAudit audit;
    /** Checked against when the username is unknown, so the answer takes as long as for a wrong password. */
    private final String unknownAccountHash;

    LoginService(UserAccountRepository accounts, EmployeeRepository employees, AuthorityResolver authorities,
                 PasswordEncoder encoder, AccessPolicy accessPolicy, LoginAudit audit) {
        this.accounts = accounts;
        this.employees = employees;
        this.authorities = authorities;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.unknownAccountHash = encoder.encode("no such account");
    }

    @Transactional
    public SessionView passwordLogin(String username, String password) {
        UserAccount account = accounts.findByUsername(UserAccount.normalize(username)).orElse(null);
        if (account == null) {
            encoder.matches(password, unknownAccountHash);
            throw refused(username, "unknown username");
        }
        if (!encoder.matches(password, account.getPasswordHash())) {
            throw refused(username, "wrong password");
        }
        if (!account.isActive()) {
            throw refused(username, "account inactive");
        }
        SessionView session = sessionOf(account);
        audit.loggedIn(session.toActor());
        return session;
    }

    /**
     * The session a BackOffice account gets. While it must change its password it holds no permission at all:
     * every use case refuses it, and so does the actuator rule.
     */
    SessionView sessionOf(UserAccount account) {
        Set<String> granted = account.isMustChangePassword() ? Set.of() : authorities.ofAccount(account);
        return SessionView.of(new Actor(account.getId(), null, null, null, granted), account.getUsername(),
                account.isMustChangePassword());
    }

    @Transactional(readOnly = true)
    public SessionView currentSession() {
        Actor actor = accessPolicy.current();
        if (actor.employeeId() != null) {
            return SessionView.of(actor, employees.findById(actor.employeeId()).map(Employee::getName).orElse(null),
                    false);
        }
        return accounts.findById(actor.userId())
                .map(account -> SessionView.of(actor, account.getUsername(), account.isMustChangePassword()))
                .orElseGet(() -> SessionView.of(actor, null, false));
    }

    private static InvalidCredentialsException refused(String username, String reason) {
        log.warn("BackOffice login refused for username '{}': {}", username, reason);
        return new InvalidCredentialsException();
    }
}
