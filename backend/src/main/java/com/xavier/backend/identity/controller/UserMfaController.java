package com.xavier.backend.identity.controller;

import com.xavier.backend.identity.model.dto.MfaResetRequest;
import com.xavier.backend.identity.model.dto.MfaResetView;
import com.xavier.backend.identity.model.dto.UserMfaView;
import com.xavier.backend.identity.service.MfaResetService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An administrator's view and reset of an account's second factor (D143). No Idempotency-Key: the reset returns a code. */
@RestController
@RequestMapping("/api/v1/backoffice/users/{id}/mfa")
class UserMfaController {

    private final MfaResetService resets;

    UserMfaController(MfaResetService resets) {
        this.resets = resets;
    }

    @GetMapping
    UserMfaView status(@PathVariable UUID id) {
        return resets.statusOf(id);
    }

    @PostMapping("/reset")
    ResponseEntity<MfaResetView> reset(@PathVariable UUID id, @Valid @RequestBody MfaResetRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(resets.reset(id, body.reason()));
    }
}
