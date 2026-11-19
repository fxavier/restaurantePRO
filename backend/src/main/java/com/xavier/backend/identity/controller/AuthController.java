package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.PinChangeRequest;
import com.xavier.backend.identity.model.dto.PosLoginRequest;
import com.xavier.backend.identity.model.dto.SessionView;
import com.xavier.backend.identity.service.LoginService;
import com.xavier.backend.identity.service.PinChangeService;
import com.xavier.backend.identity.service.PosLoginService;
import com.xavier.backend.identity.service.PosLogoutService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's session and the POS PIN login (spec §9 "Sessões", D26, D182). BackOffice login is Keycloak's, through
 * the BFF (D175, P69). The POS routes here run on the terminal's own token (D182): the PIN login answers the POS session
 * token, which the terminal sends back as {@code X-Pos-Session} with its token on every POS request.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String POS_SESSION = "X-Pos-Session";

    private final LoginService logins;
    private final PosLoginService posLogins;
    private final PinChangeService pins;
    private final PosLogoutService logouts;

    AuthController(LoginService logins, PosLoginService posLogins, PinChangeService pins, PosLogoutService logouts) {
        this.logins = logins;
        this.posLogins = posLogins;
        this.pins = pins;
        this.logouts = logouts;
    }

    /** The terminal is the one of the request's token (its {@code TERMINAL} membership). */
    @PostMapping("/pos-login")
    SessionView posLogin(@Valid @RequestBody PosLoginRequest body) {
        return posLogins.login(body.employeeCode(), body.pin());
    }

    /** After a manager's PIN reset: the same POS session now holds the employee's authorities. */
    @PostMapping("/pos-pin")
    SessionView choosePin(@Valid @RequestBody PinChangeRequest body) {
        return pins.choosePin(body.newPin());
    }

    @GetMapping("/pos-session")
    SessionView posSession() {
        return logins.currentSession();
    }

    @PostMapping("/pos-logout")
    @ResponseStatus(HttpStatus.NO_CONTENT) // documents the 204 the ResponseEntity sends
    ResponseEntity<Void> posLogout(@RequestHeader(POS_SESSION) String token) {
        logouts.logout(token);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/session")
    SessionView session() {
        return logins.currentSession();
    }
}
