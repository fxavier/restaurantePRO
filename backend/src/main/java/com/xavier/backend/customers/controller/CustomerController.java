package com.xavier.backend.customers.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.customers.model.dto.AddressRequest;
import com.xavier.backend.customers.model.dto.AddressUpdateRequest;
import com.xavier.backend.customers.model.dto.AddressView;
import com.xavier.backend.customers.model.dto.CustomerCandidate;
import com.xavier.backend.customers.model.dto.CustomerCreateRequest;
import com.xavier.backend.customers.model.dto.CustomerDetails;
import com.xavier.backend.customers.model.dto.CustomerUpdateRequest;
import com.xavier.backend.customers.model.dto.PhoneRequest;
import com.xavier.backend.customers.model.dto.PhoneView;
import com.xavier.backend.customers.service.Channel;
import com.xavier.backend.customers.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers at the POS (search, quick create, details; the session gives store and terminal) and at the BackOffice
 * (maintenance; company-wide, P38). Writes that create take an Idempotency-Key; versioned edits do not.
 */
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

    @PostMapping(BACKOFFICE)
    ResponseEntity<CustomerDetails> create(@RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody CustomerCreateRequest body, HttpServletRequest request) {
        return idempotent.execute("customers.customer.create", key, request, body, CustomerDetails.class, () -> {
            CustomerDetails created = customers.create(Channel.BACKOFFICE, body);
            return ResponseEntity.created(URI.create(BACKOFFICE + "/" + created.id())).body(created);
        });
    }

    @GetMapping(BACKOFFICE + "/{id}")
    CustomerDetails details(@PathVariable UUID id) {
        return customers.details(Channel.BACKOFFICE, id);
    }

    /** A versioned edit (D17): a stale {@code version} is a 409, so no Idempotency-Key is needed. */
    @PutMapping(BACKOFFICE + "/{id}")
    CustomerDetails update(@PathVariable UUID id, @Valid @RequestBody CustomerUpdateRequest body) {
        return customers.update(id, body);
    }

    @GetMapping(BACKOFFICE + "/{id}/phones")
    List<PhoneView> phones(@PathVariable UUID id) {
        return customers.details(Channel.BACKOFFICE, id).phones();
    }

    @PostMapping(BACKOFFICE + "/{id}/phones")
    ResponseEntity<PhoneView> addPhone(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                       @Valid @RequestBody PhoneRequest body, HttpServletRequest request) {
        return idempotent.execute("customers.phone.add", key, request, body, PhoneView.class, () -> {
            PhoneView added = customers.addPhone(id, body);
            return ResponseEntity.created(URI.create(BACKOFFICE + "/" + id + "/phones/" + added.id())).body(added);
        });
    }

    @DeleteMapping(BACKOFFICE + "/{id}/phones/{phoneId}")
    ResponseEntity<Void> removePhone(@PathVariable UUID id, @PathVariable UUID phoneId) {
        customers.removePhone(id, phoneId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(BACKOFFICE + "/{id}/addresses")
    List<AddressView> addresses(@PathVariable UUID id) {
        return customers.details(Channel.BACKOFFICE, id).addresses();
    }

    @PostMapping(BACKOFFICE + "/{id}/addresses")
    ResponseEntity<AddressView> addAddress(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                           @Valid @RequestBody AddressRequest body, HttpServletRequest request) {
        return idempotent.execute("customers.address.add", key, request, body, AddressView.class, () -> {
            AddressView added = customers.addAddress(id, body);
            return ResponseEntity.created(URI.create(BACKOFFICE + "/" + id + "/addresses/" + added.id()))
                    .body(added);
        });
    }

    @PutMapping(BACKOFFICE + "/{id}/addresses/{addressId}")
    AddressView updateAddress(@PathVariable UUID id, @PathVariable UUID addressId,
                              @Valid @RequestBody AddressUpdateRequest body) {
        return customers.updateAddress(id, addressId, body);
    }
}
