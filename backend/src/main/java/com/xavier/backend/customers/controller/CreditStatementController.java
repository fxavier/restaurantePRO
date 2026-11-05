package com.xavier.backend.customers.controller;

import com.xavier.backend.customers.model.dto.DebtRow;
import com.xavier.backend.customers.model.dto.StatementView;
import com.xavier.backend.customers.service.CreditStatementService;
import com.xavier.backend.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BackOffice statement and debts by age. */
@RestController
class CreditStatementController {

    private final CreditStatementService statements;

    CreditStatementController(CreditStatementService statements) {
        this.statements = statements;
    }

    @GetMapping(CustomerController.BACKOFFICE + "/{id}/statement")
    StatementView statement(@PathVariable UUID id, @RequestParam(required = false) Instant from,
                            @RequestParam(required = false) Instant to) {
        return statements.statement(id, from, to);
    }

    @GetMapping(CustomerController.BACKOFFICE + "/debts")
    PageResponse<DebtRow> debts(@RequestParam(required = false) Instant asOf,
                                @RequestParam(required = false) BigDecimal minBalance, Pageable pageable) {
        return statements.debts(asOf, minBalance, pageable);
    }
}
