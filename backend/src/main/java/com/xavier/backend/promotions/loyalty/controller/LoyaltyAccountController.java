package com.xavier.backend.promotions.loyalty.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.promotions.LoyaltyBalance;
import com.xavier.backend.promotions.loyalty.model.dto.AccountView;
import com.xavier.backend.promotions.loyalty.model.dto.AdjustmentRequest;
import com.xavier.backend.promotions.loyalty.model.dto.EntryView;
import com.xavier.backend.promotions.loyalty.model.dto.ReasonRequest;
import com.xavier.backend.promotions.loyalty.model.dto.StatementView;
import com.xavier.backend.promotions.loyalty.service.LoyaltyAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Loyalty members: enrollment and balance at the POS; suspension, statement and adjustments at the BackOffice. */
@RestController
class LoyaltyAccountController {

    static final String POS = "/api/v1/pos/customers/{id}/loyalty";
    static final String ACCOUNTS = "/api/v1/backoffice/loyalty/accounts/{customerId}";

    private final LoyaltyAccountService members;
    private final IdempotentRequests idempotent;

    LoyaltyAccountController(LoyaltyAccountService members, IdempotentRequests idempotent) {
        this.members = members;
        this.idempotent = idempotent;
    }

    @PostMapping(POS)
    ResponseEntity<AccountView> enroll(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       HttpServletRequest request) {
        return idempotent.execute("loyalty.account.enroll", key, request, null, AccountView.class, () ->
                ResponseEntity.created(URI.create(request.getRequestURI())).body(members.enroll(id)));
    }

    @GetMapping(POS)
    LoyaltyBalance balanceAtPos(@PathVariable UUID id) {
        return members.balanceAtPos(id);
    }

    @GetMapping(ACCOUNTS)
    LoyaltyBalance balance(@PathVariable UUID customerId) {
        return members.balance(customerId);
    }

    @PostMapping(ACCOUNTS + "/suspend")
    ResponseEntity<AccountView> suspend(@PathVariable UUID customerId, @RequestHeader("Idempotency-Key") String key,
                                        @Valid @RequestBody ReasonRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.account.suspend", key, request, body, AccountView.class,
                () -> ResponseEntity.ok(members.suspend(customerId, body)));
    }

    @PostMapping(ACCOUNTS + "/resume")
    ResponseEntity<AccountView> resume(@PathVariable UUID customerId, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody ReasonRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.account.resume", key, request, body, AccountView.class,
                () -> ResponseEntity.ok(members.resume(customerId, body)));
    }

    @GetMapping(ACCOUNTS + "/statement")
    StatementView statement(@PathVariable UUID customerId, @RequestParam(required = false) Instant from,
                            @RequestParam(required = false) Instant to) {
        return members.statement(customerId, from, to);
    }

    @PostMapping(ACCOUNTS + "/adjustments")
    ResponseEntity<EntryView> adjust(@PathVariable UUID customerId, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody AdjustmentRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.account.adjust", key, request, body, EntryView.class, () -> {
            EntryView entry = members.adjust(customerId, body);
            return ResponseEntity.created(URI.create(request.getRequestURI() + "/" + entry.id())).body(entry);
        });
    }
}
