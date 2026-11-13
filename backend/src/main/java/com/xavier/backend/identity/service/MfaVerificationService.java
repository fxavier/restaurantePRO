package com.xavier.backend.identity.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.identity.exception.InvalidCredentialsException;
import com.xavier.backend.identity.exception.MfaConflictException;
import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.model.UserAccount;
import com.xavier.backend.identity.model.dto.MfaCodeRequest;
import com.xavier.backend.identity.model.dto.MfaSessionView;
import com.xavier.backend.identity.model.dto.MfaState;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.repository.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proving the second factor (D141, D142): completing a pending login, and stepping up an existing session. The
 * caller (the controller) writes the result into the HTTP session. Failures commit (they count towards the lock).
 */
@Service
public class MfaVerificationService {

    /** A pending login must be completed within this. */
    public static final Duration PENDING_TTL = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(MfaVerificationService.class);

    /** The session the account gets after a verified login, and when the factor was proved. */
    public record Verified(SessionView session, Instant secondFactorAt) {
    }

    private final AccessPolicy accessPolicy;
    private final UserAccountRepository accounts;
    private final LoginService logins;
    private final MfaFactorCheck check;
    private final MfaPolicy policy;
    private final Clock clock;

    MfaVerificationService(AccessPolicy accessPolicy, UserAccountRepository accounts, LoginService logins,
                           MfaFactorCheck check, MfaPolicy policy, Clock clock) {
        this.accessPolicy = accessPolicy;
        this.accounts = accounts;
        this.logins = logins;
        this.check = check;
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Completes a {@code MFA_PENDING} login.
     *
     * @param state        the session's second-factor state
     * @param pendingSince when the pending login started
     * @return the session with the account's authorities, VERIFIED
     * @throws MfaConflictException 409 {@code MFA_NOT_PENDING} when the session is not pending
     * @throws MfaRefusedException  401 {@code MFA_PENDING_EXPIRED} after {@link #PENDING_TTL}, 401
     *                              {@code MFA_INVALID_CODE}, 429 {@code MFA_LOCKED}
     */
    @Transactional(noRollbackFor = MfaRefusedException.class)
    public Verified verifyLogin(MfaCodeRequest proof, MfaState state, Instant pendingSince) {
        Actor actor = accessPolicy.currentIfPresent().orElseThrow(MfaConflictException::notPending);
        if (actor.kind() != ActorKind.ACCOUNT || state != MfaState.PENDING) {
            throw MfaConflictException.notPending();
        }
        Instant now = clock.instant();
        if (pendingSince == null || !now.isBefore(pendingSince.plus(PENDING_TTL))) {
            log.warn("Second factor of user {} refused: the pending login expired", actor.userId());
            throw MfaRefusedException.pendingExpired();
        }
        UserAccount account = accounts.findById(actor.userId()).filter(UserAccount::isActive)
                .orElseThrow(InvalidCredentialsException::new);
        check.check(account.getId(), proof, "LOGIN", now);
        return new Verified(logins.sessionOf(account)
                .withMfa(MfaSessionView.of(MfaState.VERIFIED, now, policy.stepUpMaxAge())), now);
    }

    /**
     * Proves the factor again on a BackOffice session (step-up, D142); the client then repeats the refused request with
     * the same Idempotency-Key.
     *
     * @return the new {@code secondFactorAt}
     * @throws MfaConflictException 409 {@code MFA_NOT_ENROLLED} when the account has no active factor
     * @throws MfaRefusedException  401 {@code MFA_INVALID_CODE}, 429 {@code MFA_LOCKED}
     */
    @Transactional(noRollbackFor = MfaRefusedException.class)
    public Instant stepUp(MfaCodeRequest proof, MfaState state) {
        Actor actor = accessPolicy.current();
        if (actor.kind() != ActorKind.ACCOUNT) {
            throw new AccessDeniedException("Only a BackOffice account steps up.");
        }
        if (state == MfaState.PENDING || state == MfaState.ENROLMENT_REQUIRED) {
            throw new AccessDeniedException("Complete the login first.");
        }
        Instant now = clock.instant();
        check.check(actor.userId(), proof, "STEP_UP", now);
        return now;
    }
}
