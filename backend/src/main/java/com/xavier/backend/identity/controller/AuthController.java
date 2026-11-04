package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.LoginRequest;
import com.xavier.backend.identity.model.dto.PasswordChangeRequest;
import com.xavier.backend.identity.model.dto.PosLoginRequest;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.service.LoginService;
import com.xavier.backend.identity.service.PasswordService;
import com.xavier.backend.identity.service.PosLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login flows (spec §9 "Sessões", D25, D26). The routes are open in the security chain (anyone may try to log
 * in) but CSRF still applies to every POST: the SPA reads the {@code XSRF-TOKEN} cookie from any earlier GET.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final LoginService logins;
    private final PosLoginService posLogins;
    private final PasswordService passwords;
    private final LoginSessions sessions;

    AuthController(LoginService logins, PosLoginService posLogins, PasswordService passwords,
                   LoginSessions sessions) {
        this.logins = logins;
        this.posLogins = posLogins;
        this.passwords = passwords;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    SessionView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
                      HttpServletResponse response) {
        SessionView session = logins.passwordLogin(body.username(), body.password());
        sessions.start(session, request, response);
        return session;
    }

    /** The terminal is identified by its device token (from enrollment); a missing header is refused like a bad one. */
    @PostMapping("/pos-login")
    SessionView posLogin(@RequestHeader(name = "X-Terminal-Token", required = false) String terminalToken,
                         @Valid @RequestBody PosLoginRequest body, HttpServletRequest request,
                         HttpServletResponse response) {
        SessionView session = posLogins.login(terminalToken, body.employeeCode(), body.pin());
        sessions.start(session, request, response);
        return session;
    }

    /** The session is restarted (new id) with the account's real authorities. */
    @PostMapping("/password")
    SessionView changePassword(@Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request,
                               HttpServletResponse response) {
        SessionView session = passwords.change(body.currentPassword(), body.newPassword());
        sessions.start(session, request, response);
        return session;
    }

    @GetMapping("/session")
    SessionView session() {
        return logins.currentSession();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.end(request, response);
        return ResponseEntity.noContent().build();
    }
}
