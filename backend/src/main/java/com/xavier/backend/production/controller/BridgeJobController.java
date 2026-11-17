package com.xavier.backend.production.controller;

import com.xavier.backend.production.model.dto.BridgeJobView;
import com.xavier.backend.production.model.dto.BridgeOutcomeRequest;
import com.xavier.backend.production.service.BridgeJobService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The print bridge's API (D187): claim the store's next job (outbound from the store's LAN; answered at once) and
 * report its send's outcome. The bridge never names its store or tenant: both come from its {@code BRIDGE} membership.
 */
@RestController
@RequestMapping("/api/v1/bridge/jobs")
class BridgeJobController {

    private final BridgeJobService bridgeJobs;

    BridgeJobController(BridgeJobService bridgeJobs) {
        this.bridgeJobs = bridgeJobs;
    }

    /** The next due job, leased to this bridge (a list of one), or 204 at once when none is due. */
    @GetMapping
    ResponseEntity<List<BridgeJobView>> claim() {
        return bridgeJobs.claim().map(job -> ResponseEntity.ok(List.of(job)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/outcome")
    ResponseEntity<Void> outcome(@PathVariable UUID id, @Valid @RequestBody BridgeOutcomeRequest outcome) {
        bridgeJobs.recordOutcome(id, outcome);
        return ResponseEntity.noContent().build();
    }
}
