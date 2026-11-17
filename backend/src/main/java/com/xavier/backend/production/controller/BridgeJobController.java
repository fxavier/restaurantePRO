package com.xavier.backend.production.controller;

import com.xavier.backend.production.model.dto.BridgeJobView;
import com.xavier.backend.production.model.dto.BridgeOutcomeRequest;
import com.xavier.backend.production.service.BridgeJobService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The print bridge's API (D187): claim the store's jobs (a long poll, outbound from the store's LAN) and report each
 * send's outcome. The bridge never names its store or tenant: both come from its {@code BRIDGE} membership.
 */
@RestController
@RequestMapping("/api/v1/bridge/jobs")
class BridgeJobController {

    private final BridgeJobService bridgeJobs;

    BridgeJobController(BridgeJobService bridgeJobs) {
        this.bridgeJobs = bridgeJobs;
    }

    /** @param waitSeconds how long to wait for a job when there is none yet (0 to 30) */
    @GetMapping
    List<BridgeJobView> claim(@RequestParam(defaultValue = "10") int limit,
                              @RequestParam(defaultValue = "0") long waitSeconds) throws InterruptedException {
        return bridgeJobs.claim(limit, Duration.ofSeconds(Math.max(0, waitSeconds)));
    }

    @PostMapping("/{id}/outcome")
    ResponseEntity<Void> outcome(@PathVariable UUID id, @Valid @RequestBody BridgeOutcomeRequest outcome) {
        bridgeJobs.recordOutcome(id, outcome);
        return ResponseEntity.noContent().build();
    }
}
