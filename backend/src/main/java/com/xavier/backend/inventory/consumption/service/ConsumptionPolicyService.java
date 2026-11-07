package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.consumption.model.ConsumptionPolicyVersion;
import com.xavier.backend.inventory.consumption.model.PolicyRuleLine;
import com.xavier.backend.inventory.consumption.model.dto.PolicyVersionRequest;
import com.xavier.backend.inventory.consumption.model.dto.PolicyVersionView;
import com.xavier.backend.inventory.consumption.repository.ConsumptionPolicyRepository;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import com.xavier.backend.inventory.recipe.model.dto.ActivateRequest;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumption policy (D116), company-wide, {@code RECIPE_MANAGE} (writes installation-wide): versions of
 * per-family rules, edited while DRAFT and activated like recipe versions (never in the past, after the version in
 * force, which they supersede). Audited.
 */
@Service
public class ConsumptionPolicyService {

    static final String ENTITY = "ConsumptionPolicy";

    private final ConsumptionPolicyRepository policies;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    ConsumptionPolicyService(ConsumptionPolicyRepository policies, AccessPolicy accessPolicy, AuditLog auditLog,
                             Clock clock) {
        this.policies = policies;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PolicyVersionView> list() {
        accessPolicy.requireInAnyStore(Permission.RECIPE_MANAGE);
        return policies.findAllByOrderByVersionNo().stream().map(ConsumptionPolicyVersion::toView).toList();
    }

    @Transactional
    public PolicyVersionView create(PolicyVersionRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        ConsumptionPolicyVersion version = UniqueConstraints.saveAndFlush(policies,
                ConsumptionPolicyVersion.draft(policies.maxVersionNo() + 1, rules(request), clock.instant()),
                RecipeConflictException::policyVersionTaken,
                "consumption_policy_version_no_uk");
        audit("CONSUMPTION_POLICY_CREATED", version, Map.of());
        return version.toView();
    }

    @Transactional
    public PolicyVersionView revise(int versionNo, PolicyVersionRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        ConsumptionPolicyVersion version = require(versionNo);
        if (request.version() != null) {
            OptimisticLocking.requireVersion(ConsumptionPolicyVersion.class, version.getId(), version.getVersion(),
                    request.version());
        }
        List<PolicyRuleLine> rules = rules(request);
        version.revise(List.of());
        policies.flush(); // the old rules go first: a family keeps one rule per version (unique index)
        version.revise(rules);
        policies.flush();
        audit("CONSUMPTION_POLICY_REVISED", version, Map.of());
        return version.toView();
    }

    /** DRAFT → ACTIVE from {@code effectiveFrom} (now when absent), superseding the version in force then. */
    @Transactional
    public PolicyVersionView activate(int versionNo, ActivateRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        ConsumptionPolicyVersion version = require(versionNo);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw RecipeConflictException.immutable(version.getId(), version.getStatus(), "be activated");
        }
        Instant now = clock.instant();
        Instant from = request == null || request.effectiveFrom() == null ? now : request.effectiveFrom();
        if (from.isBefore(now)) {
            throw RecipeRuleException.effectiveFromInPast(now);
        }
        Optional<ConsumptionPolicyVersion> current = policies.findByPeriodStatus(VersionStatus.ACTIVE);
        current.ifPresent(active -> {
            active.supersedeAt(from);
            policies.flush();
        });
        version.activate(from, now);
        UniqueConstraints.saveAndFlush(policies, version, RecipeConflictException::policyVersionTaken,
                "consumption_policy_one_active_uk");
        audit("CONSUMPTION_POLICY_ACTIVATED", version, Map.of("effectiveFrom", from.toString()));
        return version.toView();
    }

    private static List<PolicyRuleLine> rules(PolicyVersionRequest request) {
        Set<UUID> families = new HashSet<>();
        boolean[] defaultSeen = {false};
        return request.rules().stream().map(rule -> {
            boolean duplicate = rule.familyId() == null ? defaultSeen[0] : !families.add(rule.familyId());
            defaultSeen[0] |= rule.familyId() == null;
            if (duplicate) {
                throw RecipeRuleException.policyRuleDuplicate(rule.familyId());
            }
            return new PolicyRuleLine(rule.familyId(), rule.tracked());
        }).toList();
    }

    private ConsumptionPolicyVersion require(int versionNo) {
        return policies.findByVersionNo(versionNo)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, versionNo));
    }

    private void audit(String action, ConsumptionPolicyVersion version, Map<String, Object> details) {
        Map<String, Object> all = new java.util.HashMap<>(details);
        all.put("versionNo", version.getVersionNo());
        auditLog.recordAsCurrentActor(action, ENTITY, version.getId().toString(), null, null, null, all);
    }
}
