package com.xavier.backend.customers.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.customers.CreditView;
import com.xavier.backend.customers.model.dto.CreditBlockRequest;
import com.xavier.backend.customers.model.dto.CreditLimitChangeRequest;
import com.xavier.backend.customers.model.dto.CreditUnblockRequest;
import com.xavier.backend.customers.service.Channel;
import com.xavier.backend.customers.service.CreditAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** A customer's credit: the available credit (POS, BackOffice) and its limit and block (BackOffice). */
@RestController
class CreditAccountController {

    private static final String POS = CustomerController.POS + "/{id}/credit";
    private static final String BACKOFFICE = CustomerController.BACKOFFICE + "/{id}";

    private final CreditAccountService credit;
    private final IdempotentRequests idempotent;

    CreditAccountController(CreditAccountService credit, IdempotentRequests idempotent) {
        this.credit = credit;
        this.idempotent = idempotent;
    }

    @GetMapping(POS)
    CreditView creditAtPos(@PathVariable UUID id) {
        return credit.credit(Channel.POS, id);
    }

    @GetMapping(BACKOFFICE + "/credit")
    CreditView credit(@PathVariable UUID id) {
        return credit.credit(Channel.BACKOFFICE, id);
    }

    @PostMapping(BACKOFFICE + "/credit-limit-changes")
    ResponseEntity<CreditView> changeLimit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody CreditLimitChangeRequest body,
                                           HttpServletRequest request) {
        return idempotent.execute("customers.credit.limit-change", key, request, body, CreditView.class,
                () -> ResponseEntity.ok(credit.changeLimit(id, body)));
    }

    @PostMapping(BACKOFFICE + "/credit-block")
    ResponseEntity<CreditView> block(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                     @Valid @RequestBody CreditBlockRequest body, HttpServletRequest request) {
        return idempotent.execute("customers.credit.block", key, request, body, CreditView.class,
                () -> ResponseEntity.ok(credit.block(id, body)));
    }

    @PostMapping(BACKOFFICE + "/credit-unblock")
    ResponseEntity<CreditView> unblock(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody CreditUnblockRequest body, HttpServletRequest request) {
        return idempotent.execute("customers.credit.unblock", key, request, body, CreditView.class,
                () -> ResponseEntity.ok(credit.unblock(id, body)));
    }
}
