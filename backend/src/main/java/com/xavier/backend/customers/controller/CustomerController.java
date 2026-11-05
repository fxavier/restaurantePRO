package com.xavier.backend.customers.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.customers.model.dto.CustomerCandidate;
import com.xavier.backend.customers.model.dto.CustomerCreateRequest;
import com.xavier.backend.customers.model.dto.CustomerDetails;
import com.xavier.backend.customers.service.Channel;
import com.xavier.backend.customers.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Customer search and quick create at the POS (D72): the store and terminal come from the session. */
@RestController
class CustomerController {

    static final String POS = "/api/v1/pos/customers";
    static final String BACKOFFICE = "/api/v1/backoffice/customers";

    private final CustomerService customers;
    private final IdempotentRequests idempotent;

    CustomerController(CustomerService customers, IdempotentRequests idempotent) {
        this.customers = customers;
        this.idempotent = idempotent;
    }

    @GetMapping(POS)
    List<CustomerCandidate> searchAtPos(@RequestParam(required = false) @SearchText String q) {
        return customers.search(Channel.POS, q);
    }

    @GetMapping(BACKOFFICE)
    List<CustomerCandidate> search(@RequestParam(required = false) @SearchText String q) {
        return customers.search(Channel.BACKOFFICE, q);
    }

    @PostMapping(POS)
    ResponseEntity<CustomerDetails> createAtPos(@RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CustomerCreateRequest body,
                                                HttpServletRequest request) {
        return idempotent.execute("customers.customer.create", key, request, body, CustomerDetails.class, () -> {
            CustomerDetails created = customers.create(Channel.POS, body);
            return ResponseEntity.created(URI.create(POS + "/" + created.id())).body(created);
        });
    }

    @GetMapping(POS + "/{id}")
    CustomerDetails detailsAtPos(@PathVariable UUID id) {
        return customers.details(Channel.POS, id);
    }
}
