package com.xavier.backend.settlement.controller;

import com.xavier.backend.settlement.model.dto.PaymentMethodCreateRequest;
import com.xavier.backend.settlement.model.dto.PaymentMethodUpdateRequest;
import com.xavier.backend.settlement.model.dto.PaymentMethodView;
import com.xavier.backend.settlement.service.PaymentMethodService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment methods in the BackOffice (D52). */
@RestController
@RequestMapping(PaymentMethodController.PATH)
class PaymentMethodController {

    static final String PATH = "/api/v1/backoffice/payment-methods";

    private final PaymentMethodService methods;

    PaymentMethodController(PaymentMethodService methods) {
        this.methods = methods;
    }

    @GetMapping
    List<PaymentMethodView> list() {
        return methods.list();
    }

    @PostMapping
    ResponseEntity<PaymentMethodView> create(@Valid @RequestBody PaymentMethodCreateRequest body) {
        PaymentMethodView created = methods.create(body);
        return ResponseEntity.created(URI.create(PATH + "/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    PaymentMethodView update(@PathVariable UUID id, @Valid @RequestBody PaymentMethodUpdateRequest body) {
        return methods.update(id, body);
    }
}
