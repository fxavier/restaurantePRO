package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.exception.MfaRefusedException;
import com.xavier.backend.identity.model.dto.MfaCodeRequest;
import com.xavier.backend.identity.model.dto.MfaConfirmRequest;
import com.xavier.backend.identity.model.dto.MfaEnrolledView;
import com.xavier.backend.identity.model.dto.MfaEnrolmentRequest;
import com.xavier.backend.identity.model.dto.MfaEnrolmentView;
import com.xavier.backend.identity.model.dto.MfaStatusView;
import com.xavier.backend.identity.model.dto.RecoveryCodesView;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.service.MfaEnrolmentService;
import com.xavier.backend.identity.service.MfaSessionAttributes;
import com.xavier.backend.identity.service.MfaVerificationService;
import com.xavier.backend.identity.service.RemoteAddresses;
import com.xavier.backend.identity.service.SessionHandles;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The second factor of the caller's own BackOffice session (D139-D143). Under {@code /api/v1/auth/**}, so a session in
 * {@code MFA_PENDING} or {@code MFA_ENROLMENT_REQUIRED} reaches it; every use case checks who calls. Responses with a
 * secret or codes take no Idempotency-Key (D143: nothing secret in {@code shared.idempotency_record}) and are marked
 * {@code no-store}.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
class MfaController {

    private final MfaVerificationService verification;
    private final MfaEnrolmentService enrolment;
    private final LoginSessions sessions;
    private final RemoteAddresses addresses;

    MfaController(MfaVerificationService verification, MfaEnrolmentService enrolment, LoginSessions sessions,
                  RemoteAddresses addresses) {
        this.verification = verification;
        this.enrolment = enrolment;
        this.sessions = sessions;
        this.addresses = addresses;
    }

    @GetMapping
    MfaStatusView status(HttpServletRequest request) {
        return enrolment.status(MfaSessionAttributes.secondFactorAt(request.getSession(false)));
    }

    /**
     * Completes a pending login: the session restarts (new id) with the account's authorities. A pending login that
     * expired or locked the factor is ended.
     */
    @PostMapping("/verify")
    SessionView verify(@Valid @RequestBody MfaCodeRequest body, HttpServletRequest request,
                       HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        MfaVerificationService.Verified verified;
        try {
            verified = verification.verifyLogin(body, MfaSessionAttributes.state(session),
                    MfaSessionAttributes.pendingSince(session));
        } catch (MfaRefusedException e) {
            if (session != null && !MfaRefusedException.INVALID_CODE.equals(e.code())) {
                session.invalidate(); // D139: a pending login ends when it expires or locks the factor
            }
            throw e;
        }
        String id = sessions.start(verified.session(), verified.secondFactorAt(), request, response);
        return verified.session().withSessionHandle(SessionHandles.of(id));
    }

    /** Proves the factor again; the client repeats the refused request with the same Idempotency-Key (D142). */
    @PostMapping("/step-up")
    MfaStatusView stepUp(@Valid @RequestBody MfaCodeRequest body, HttpServletRequest request) {
        Instant at = verification.stepUp(body, MfaSessionAttributes.state(request.getSession(false)));
        sessions.steppedUp(request, at);
        return enrolment.status(at);
    }

    @PostMapping("/enrolment")
    ResponseEntity<MfaEnrolmentView> startEnrolment(@Valid @RequestBody MfaEnrolmentRequest body,
                                                    HttpServletRequest request) {
        return noStore(enrolment.start(body.currentPassword(), body.enrolmentCode(), addresses.isRemote(request)));
    }

    /** Activates the factor; the session restarts (new id) as VERIFIED with the account's authorities. */
    @PostMapping("/enrolment/confirm")
    ResponseEntity<MfaEnrolledView> confirmEnrolment(@Valid @RequestBody MfaConfirmRequest body,
                                                     HttpServletRequest request, HttpServletResponse response) {
        MfaEnrolmentService.Enrolled enrolled = enrolment.confirm(body.totp());
        SessionView session = enrolled.view().session();
        String id = sessions.start(session, enrolled.secondFactorAt(), request, response);
        return noStore(new MfaEnrolledView(enrolled.view().recoveryCodes(),
                session.withSessionHandle(SessionHandles.of(id))));
    }

    @PostMapping("/recovery-codes")
    ResponseEntity<RecoveryCodesView> regenerateRecoveryCodes(HttpServletRequest request) {
        return noStore(new RecoveryCodesView(enrolment.regenerateRecoveryCodes(
                MfaSessionAttributes.secondFactorAt(request.getSession(false)))));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
