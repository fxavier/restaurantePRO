package com.xavier.backend.inventory.consumption.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.inventory.consumption.model.dto.PolicyVersionRequest;
import com.xavier.backend.inventory.consumption.model.dto.PolicyVersionView;
import com.xavier.backend.inventory.consumption.service.ConsumptionPolicyService;
import com.xavier.backend.inventory.recipe.model.dto.ActivateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The consumption policy's versions and rules (D116), {@code RECIPE_MANAGE}; writes take an Idempotency-Key. */
@RestController
class ConsumptionPolicyController {

    static final String PATH = "/api/v1/backoffice/inventory/consumption-policies";

    private final ConsumptionPolicyService policies;
    private final IdempotentRequests idempotent;

    ConsumptionPolicyController(ConsumptionPolicyService policies, IdempotentRequests idempotent) {
        this.policies = policies;
        this.idempotent = idempotent;
    }

    @GetMapping(PATH)
    List<PolicyVersionView> list() {
        return policies.list();
    }

    @PostMapping(PATH)
    ResponseEntity<PolicyVersionView> create(@RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody PolicyVersionRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.consumption-policy.create", key, request, body,
                PolicyVersionView.class, () -> {
                    PolicyVersionView created = policies.create(body);
                    return ResponseEntity.created(URI.create(PATH + "/" + created.versionNo())).body(created);
                });
    }

    @PutMapping(PATH + "/{versionNo}")
    ResponseEntity<PolicyVersionView> revise(@PathVariable int versionNo, @RequestHeader("Idempotency-Key") String key,
                                             @Valid @RequestBody PolicyVersionRequest body,
                                             HttpServletRequest request) {
        return idempotent.execute("inventory.consumption-policy.revise", key, request, body,
                PolicyVersionView.class, () -> ResponseEntity.ok(policies.revise(versionNo, body)));
    }

    @PostMapping(PATH + "/{versionNo}/activate")
    ResponseEntity<PolicyVersionView> activate(@PathVariable int versionNo,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @RequestBody(required = false) ActivateRequest body,
                                               HttpServletRequest request) {
        ActivateRequest activation = body == null ? new ActivateRequest(null) : body;
        return idempotent.execute("inventory.consumption-policy.activate", key, request, activation,
                PolicyVersionView.class, () -> ResponseEntity.ok(policies.activate(versionNo, activation)));
    }
}
