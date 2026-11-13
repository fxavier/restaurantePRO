package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaSessionView;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.EmployeeRepository;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BackOffice login (username + password, D26) and the description of the current session. Every refusal is the
 * same {@link InvalidCredentialsException} (401); the reason goes to a WARN log line, never the password. Password
 * guessing is throttled per username and client address ({@link LoginThrottling}, 429). A support account logs in
 * only while it holds a usable support grant (D83); without one it gets the same 401 as a wrong password. A right
 * password then meets the second-factor rule of the installation ({@link MfaGate}, D140, D141).
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
    private final LoginThrottling throttling;
    private final SupportGrantService supportGrants;
    private final MfaGate mfaGate;
    private final MfaPolicy mfaPolicy;
    /** Checked against when the username is unknown, so the answer takes as long as for a wrong password. */
    private final String unknownAccountHash;

    LoginService(UserAccountRepository accounts, EmployeeRepository employees, AuthorityResolver authorities,
                 PasswordEncoder encoder, AccessPolicy accessPolicy, LoginAudit audit, LoginThrottling throttling,
                 SupportGrantService supportGrants, MfaGate mfaGate, MfaPolicy mfaPolicy) {
        this.accounts = accounts;
        this.employees = employees;
        this.authorities = authorities;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.throttling = throttling;
        this.supportGrants = supportGrants;
        this.mfaGate = mfaGate;
        this.mfaPolicy = mfaPolicy;
        this.unknownAccountHash = encoder.encode("no such account");
    }

    /**
     * Not transactional: the attempt count commits on its own (a refusal rolls nothing back), and the BCrypt check
     * holds no connection.
     *
     * @param clientAddress where the attempt comes from, for throttling
     * @param remote        the request is from outside {@code lan-cidrs} (D140)
     * @param enrolmentCode optional: lets an account without a factor log in to enrol where that needs a code (D143)
     * @return the session; in {@code MFA_PENDING} or {@code MFA_ENROLMENT_REQUIRED} it holds no authority
     */
    public SessionView passwordLogin(String username, String password, String clientAddress, boolean remote,
                                     String enrolmentCode) {
        String normalized = UserAccount.normalize(username);
        UserAccount account = accounts.findByUsername(normalized).orElse(null);
        throttling.countAttempt(normalized, clientAddress, account == null ? null : account.getId());
        String refusal = refusalOf(account, password);
        // looked up whatever the password check said, so the answer's timing does not tell a right password apart
        Optional<UUID> supportGrant = account != null && account.isSupport()
                ? supportGrants.usableGrantOf(account.getId()) : Optional.empty();
        if (refusal == null && account.isSupport() && supportGrant.isEmpty()) {
            refusal = "support account without an active support grant";
        }
        if (refusal != null) {
            throttling.failed(clientAddress);
            throw refused(username, refusal);
        }
        throttling.succeeded(normalized, clientAddress);
        SessionView session = mfaGate.atLogin(account, sessionOf(account), remote, enrolmentCode);
        Map<String, Object> details = new LinkedHashMap<>();
        supportGrant.ifPresent(id -> details.put("supportGrantId", id));
        if (session.mfa().state() != MfaState.NONE) {
            details.put("mfa", session.mfa().state().name());
        }
        audit.loggedIn(session.toActor(), details);
        return session;
    }

    /** @return why the login is refused, or {@code null}; always one BCrypt check, so every answer takes as long */
    private String refusalOf(UserAccount account, String password) {
        if (account == null) {
            encoder.matches(password, unknownAccountHash);
            return "unknown username";
        }
        if (!encoder.matches(password, account.getPasswordHash())) {
            return "wrong password";
        }
        return account.isActive() ? null : "account inactive";
    }

    /**
     * The session a BackOffice account gets. While it must change its password it holds no permission at all:
     * every use case refuses it, and so does the actuator rule.
     */
    SessionView sessionOf(UserAccount account) {
        Set<String> granted = account.isMustChangePassword() ? Set.of() : authorities.ofAccount(account);
        return SessionView.account(Actor.account(account.getId(), granted), account.getUsername(),
                account.isMustChangePassword());
    }

    /** The caller's session, with its second-factor state read from the HTTP session (D141). */
    @Transactional(readOnly = true)
    public SessionView currentSession() {
        Actor actor = accessPolicy.current();
        if (actor.kind() == ActorKind.EMPLOYEE) {
            return employees.findById(actor.employeeId())
                    .map(employee -> SessionView.employee(actor, employee.getName(), employee.isMustChangePin()))
                    .orElseGet(() -> SessionView.employee(actor, null, false));
        }
        MfaSessionView mfa = MfaSessionAttributes.current()
                .map(session -> MfaSessionView.of(MfaSessionAttributes.state(session),
                        MfaSessionAttributes.secondFactorAt(session), mfaPolicy.stepUpMaxAge()))
                .orElse(MfaSessionView.NONE);
        return accounts.findById(actor.userId())
                .map(account -> SessionView.account(actor, account.getUsername(), account.isMustChangePassword()))
                .orElseGet(() -> SessionView.account(actor, null, false))
                .withMfa(mfa);
    }

    private static InvalidCredentialsException refused(String username, String reason) {
        log.warn("BackOffice login refused for username '{}': {}", username, reason);
        return new InvalidCredentialsException();
    }
}
