package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.audit.AuditEntry;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.exception.NoPasswordException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.exception.WeakPasswordException;
import com.xavier.backend.identity.exception.WrongCurrentPasswordException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A BackOffice account changes its own password (D26, D30: required at first login). */
@Service
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    private final UserAccountRepository accounts;
    private final LoginService logins;
    private final PasswordEncoder encoder;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final SessionTermination sessions;
    private final Clock clock;

    PasswordService(UserAccountRepository accounts, LoginService logins, PasswordEncoder encoder,
                    AccessPolicy accessPolicy, AuditLog auditLog, SessionTermination sessions, Clock clock) {
        this.accounts = accounts;
        this.logins = logins;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * Needs only an authenticated BackOffice session (no permission: an account that must change its password
     * holds none). Clears {@code mustChangePassword} and ends the account's other sessions (whoever else knew the
     * old password is logged out).
     *
     * @param currentSessionId the caller's session, which stays; {@code null} if none
     * @return the session the account now gets, with its real authorities
     */
    @Transactional
    public SessionView change(String currentPassword, String newPassword, String currentSessionId) {
        Actor actor = accessPolicy.current();
        if (actor.employeeId() != null) {
            throw new NoPasswordException();
        }
        UserAccount account = accounts.findById(actor.userId())
                .orElseThrow(() -> new UserNotFoundException(actor.userId()));
        if (!account.isActive()) {
            log.warn("Password change refused for user {}: account inactive", account.getId());
            throw new InvalidCredentialsException();
        }
        if (!encoder.matches(currentPassword, account.getPasswordHash())) {
            log.warn("Password change refused for user {}: wrong current password", account.getId());
            throw new WrongCurrentPasswordException();
        }
        PasswordPolicy.requireStrong(newPassword);
        if (encoder.matches(newPassword, account.getPasswordHash())) {
            throw new WeakPasswordException("The new password must differ from the current one.");
        }
        account.changePassword(encoder.encode(newPassword), clock.instant());
        accounts.saveAndFlush(account);
        auditLog.record(new AuditEntry(account.getId(), null, "USER_PASSWORD_CHANGED", "UserAccount",
                account.getId().toString(), null, null, null, Map.of("username", account.getUsername())));
        sessions.endAllExcept(account.getId(), currentSessionId);
        return logins.sessionOf(account);
    }
}
