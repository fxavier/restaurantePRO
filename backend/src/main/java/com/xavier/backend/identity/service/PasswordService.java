package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.exception.NoPasswordException;
import com.xavier.backend.identity.exception.UserNotFoundException;
import com.xavier.backend.identity.exception.WeakPasswordException;
import com.xavier.backend.identity.exception.WrongCurrentPasswordException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
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
    private final MfaGate mfaGate;
    private final Clock clock;

    PasswordService(UserAccountRepository accounts, LoginService logins, PasswordEncoder encoder,
                    AccessPolicy accessPolicy, AuditLog auditLog, SessionTermination sessions, MfaGate mfaGate,
                    Clock clock) {
        this.accounts = accounts;
        this.logins = logins;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.sessions = sessions;
        this.mfaGate = mfaGate;
        this.clock = clock;
    }

    /**
     * Needs only an authenticated BackOffice session (no permission: an account that must change its password
     * holds none). Clears {@code mustChangePassword} and ends the account's other sessions (whoever else knew the
     * old password is logged out).
     *
     * @param currentSessionId the caller's session, which stays; {@code null} if none
     * @param mfaState         the caller's second-factor state: a {@code PENDING} session (the password alone) may not
     *                         change the password, or the password alone could lock the owner out
     * @param remote           the request is remote (D140), for the second-factor rule the new session meets
     * @param secondFactorAt   when the caller's session proved its second factor, or {@code null}
     * @return the session the account now gets: its real authorities, or the second-factor state D141 asks for
     */
    @Transactional
    public SessionView change(String currentPassword, String newPassword, String currentSessionId, MfaState mfaState,
                              boolean remote, Instant secondFactorAt) {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.ACCOUNT) {
            throw new NoPasswordException();
        }
        if (mfaState == MfaState.PENDING) {
            log.warn("Password change refused for user {}: the second factor is not proved yet", actor.userId());
            throw new AccessDeniedException("Confirm your second factor first.");
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
        auditLog.recordAsCurrentActor("USER_PASSWORD_CHANGED", "UserAccount", account.getId().toString(), null,
                null, null, Map.of("username", account.getUsername()));
        sessions.endAccountSessionsExcept(account.getId(), currentSessionId);
        return mfaGate.afterPasswordChange(account, logins.sessionOf(account), remote, secondFactorAt);
    }
}
