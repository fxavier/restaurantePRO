package com.xavier.backend.promotions.loyalty.controller;

import com.xavier.backend.access.IdempotentRequests;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionEditRequest;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionRequest;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionView;
import com.xavier.backend.promotions.loyalty.model.dto.PublishRequest;
import com.xavier.backend.promotions.loyalty.model.dto.RewardRequest;
import com.xavier.backend.promotions.loyalty.model.dto.RewardView;
import com.xavier.backend.promotions.loyalty.service.LoyaltyProgramService;
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
import org.springframework.web.bind.annotation.RestController;

/** BackOffice: loyalty program versions and their rewards (D111, D112). */
@RestController
class LoyaltyProgramController {

    static final String PROGRAMS = "/api/v1/backoffice/loyalty/program-versions";

    private final LoyaltyProgramService programs;
    private final IdempotentRequests idempotent;

    LoyaltyProgramController(LoyaltyProgramService programs, IdempotentRequests idempotent) {
        this.programs = programs;
        this.idempotent = idempotent;
    }

    @PostMapping(PROGRAMS)
    ResponseEntity<ProgramVersionView> create(@RequestHeader("Idempotency-Key") String key,
                                              @Valid @RequestBody ProgramVersionRequest body,
                                              HttpServletRequest request) {
        return idempotent.execute("loyalty.program.create", key, request, body, ProgramVersionView.class, () -> {
            ProgramVersionView created = programs.create(body);
            return ResponseEntity.created(URI.create(PROGRAMS + "/" + created.id())).body(created);
        });
    }

    @GetMapping(PROGRAMS)
    List<ProgramVersionView> list() {
        return programs.list();
    }

    @GetMapping(PROGRAMS + "/{id}")
    ProgramVersionView get(@PathVariable UUID id) {
        return programs.get(id);
    }

    @PutMapping(PROGRAMS + "/{id}")
    ResponseEntity<ProgramVersionView> edit(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                            @Valid @RequestBody ProgramVersionEditRequest body,
                                            HttpServletRequest request) {
        return idempotent.execute("loyalty.program.edit", key, request, body, ProgramVersionView.class,
                () -> ResponseEntity.ok(programs.edit(id, body)));
    }

    @PostMapping(PROGRAMS + "/{id}/publish")
    ResponseEntity<ProgramVersionView> publish(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody PublishRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.program.publish", key, request, body, ProgramVersionView.class,
                () -> ResponseEntity.ok(programs.publish(id, body)));
    }

    @PostMapping(PROGRAMS + "/{id}/rewards")
    ResponseEntity<RewardView> addReward(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                         @Valid @RequestBody RewardRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.reward.add", key, request, body, RewardView.class, () -> {
            RewardView created = programs.addReward(id, body);
            return ResponseEntity.created(URI.create(PROGRAMS + "/" + id + "/rewards/" + created.id())).body(created);
        });
    }

    @PutMapping(PROGRAMS + "/{id}/rewards/{rewardId}")
    ResponseEntity<RewardView> editReward(@PathVariable UUID id, @PathVariable UUID rewardId,
                                          @RequestHeader("Idempotency-Key") String key,
                                          @Valid @RequestBody RewardRequest body, HttpServletRequest request) {
        return idempotent.execute("loyalty.reward.edit", key, request, body, RewardView.class,
                () -> ResponseEntity.ok(programs.editReward(id, rewardId, body)));
    }

    @DeleteMapping(PROGRAMS + "/{id}/rewards/{rewardId}")
    ResponseEntity<Void> removeReward(@PathVariable UUID id, @PathVariable UUID rewardId) {
        programs.removeReward(id, rewardId);
        return ResponseEntity.noContent().build();
    }
}
