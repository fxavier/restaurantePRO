package com.xavier.backend.promotions.loyalty.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyNotFoundException;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyRuleException;
import com.xavier.backend.promotions.loyalty.model.LoyaltyProgramVersion;
import com.xavier.backend.promotions.loyalty.model.LoyaltyReward;
import com.xavier.backend.promotions.loyalty.model.ProgramStatus;
import com.xavier.backend.promotions.loyalty.model.RewardEffect;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionEditRequest;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionRequest;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionView;
import com.xavier.backend.promotions.loyalty.model.dto.PublishRequest;
import com.xavier.backend.promotions.loyalty.model.dto.RewardRequest;
import com.xavier.backend.promotions.loyalty.model.dto.RewardView;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyProgramVersionRepository;
import com.xavier.backend.promotions.loyalty.repository.LoyaltyRewardRepository;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company-wide loyalty program (D111): DRAFT versions with their rule, scope and rewards, and publishing one, which
 * retires the version in force. {@code LOYALTY_PROGRAM_MANAGE} installation-wide only: the program is company-wide
 * and administrator only (D134), like the credit limits (S20); a grant in one store is not enough. Every change is
 * audited.
 */
@Service
public class LoyaltyProgramService {

    private final LoyaltyProgramVersionRepository versions;
    private final LoyaltyRewardRepository rewards;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    LoyaltyProgramService(LoyaltyProgramVersionRepository versions, LoyaltyRewardRepository rewards,
                          OrganizationApi organization, AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.versions = versions;
        this.rewards = rewards;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public ProgramVersionView create(ProgramVersionRequest request) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        Currency currency = organization.companyProfile().currency();
        LoyaltyProgramVersion draft = LoyaltyProgramVersion.draft(versions.maxVersionNo() + 1,
                money(request.spendUnit(), currency), request.pointsPerUnit(), orEmpty(request.itemIds()),
                orEmpty(request.familyIds()), clock.instant());
        draft = UniqueConstraints.saveAndFlush(versions, draft, LoyaltyConflictException::programConcurrent,
                LoyaltyProgramVersion.VERSION_NO_UK);
        audit("LOYALTY_PROGRAM_CREATED", draft);
        return view(draft);
    }

    @Transactional
    public ProgramVersionView edit(UUID versionId, ProgramVersionEditRequest request) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        LoyaltyProgramVersion draft = requireVersion(versionId);
        draft.requireDraft("be edited");
        OptimisticLocking.requireVersion(LoyaltyProgramVersion.class, versionId, draft.getVersion(),
                request.version());
        draft.edit(money(request.spendUnit(), draft.currency()), request.pointsPerUnit(),
                orEmpty(request.itemIds()), orEmpty(request.familyIds()));
        versions.saveAndFlush(draft);
        audit("LOYALTY_PROGRAM_EDITED", draft);
        return view(draft);
    }

    /** DRAFT → PUBLISHED; the version in force is RETIRED at the same instant. */
    @Transactional
    public ProgramVersionView publish(UUID versionId, PublishRequest request) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        LoyaltyProgramVersion draft = requireVersion(versionId);
        draft.requireDraft("be published");
        OptimisticLocking.requireVersion(LoyaltyProgramVersion.class, versionId, draft.getVersion(),
                request.version());
        Instant now = clock.instant();
        versions.findByStatus(ProgramStatus.PUBLISHED).ifPresent(inForce -> {
            inForce.retire(now);
            versions.saveAndFlush(inForce);
        });
        draft.publish(accessPolicy.current().userId(), now);
        draft = UniqueConstraints.saveAndFlush(versions, draft, LoyaltyConflictException::programConcurrent,
                LoyaltyProgramVersion.PUBLISHED_UK);
        audit("LOYALTY_PROGRAM_PUBLISHED", draft);
        return view(draft);
    }

    @Transactional(readOnly = true)
    public List<ProgramVersionView> list() {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        List<LoyaltyProgramVersion> all = versions.findAllByOrderByVersionNoDesc();
        Map<UUID, List<RewardView>> byVersion = rewards.findByVersionIdInOrderByCode(
                        all.stream().map(LoyaltyProgramVersion::getId).toList()).stream()
                .collect(Collectors.groupingBy(LoyaltyReward::getVersionId,
                        Collectors.mapping(LoyaltyReward::toView, Collectors.toList())));
        return all.stream().map(v -> v.toView(byVersion.getOrDefault(v.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public ProgramVersionView get(UUID versionId) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        return view(requireVersion(versionId));
    }

    @Transactional
    public RewardView addReward(UUID versionId, RewardRequest request) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        LoyaltyProgramVersion draft = requireVersion(versionId);
        draft.requireDraft("get rewards");
        requireValid(request);
        LoyaltyReward reward = LoyaltyReward.of(versionId, request.code(), request.name(), request.pointsCost(),
                request.effect(), amount(request, draft.currency()), request.itemId(), request.familyId());
        reward = UniqueConstraints.saveAndFlush(rewards, reward,
                () -> LoyaltyConflictException.rewardCodeTaken(request.code().strip()), LoyaltyReward.CODE_UK);
        auditReward("LOYALTY_REWARD_ADDED", reward);
        return reward.toView();
    }

    @Transactional
    public RewardView editReward(UUID versionId, UUID rewardId, RewardRequest request) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        LoyaltyProgramVersion draft = requireVersion(versionId);
        draft.requireDraft("change its rewards");
        LoyaltyReward reward = requireReward(versionId, rewardId);
        requireValid(request);
        reward.change(request.name(), request.pointsCost(), request.effect(), amount(request, draft.currency()),
                request.itemId(), request.familyId());
        rewards.saveAndFlush(reward);
        auditReward("LOYALTY_REWARD_CHANGED", reward);
        return reward.toView();
    }

    @Transactional
    public void removeReward(UUID versionId, UUID rewardId) {
        accessPolicy.require(Permission.LOYALTY_PROGRAM_MANAGE);
        requireVersion(versionId).requireDraft("change its rewards");
        LoyaltyReward reward = requireReward(versionId, rewardId);
        rewards.delete(reward);
        rewards.flush();
        auditReward("LOYALTY_REWARD_REMOVED", reward);
    }

    private ProgramVersionView view(LoyaltyProgramVersion version) {
        return version.toView(rewards.findByVersionIdOrderByCode(version.getId()).stream()
                .map(LoyaltyReward::toView).toList());
    }

    private LoyaltyProgramVersion requireVersion(UUID versionId) {
        return versions.findById(versionId)
                .orElseThrow(() -> new LoyaltyNotFoundException(LoyaltyProgramVersion.ENTITY, versionId));
    }

    private LoyaltyReward requireReward(UUID versionId, UUID rewardId) {
        return rewards.findByIdAndVersionId(rewardId, versionId)
                .orElseThrow(() -> new LoyaltyNotFoundException(LoyaltyReward.ENTITY, rewardId));
    }

    private static void requireValid(RewardRequest request) {
        if ((request.effect() == RewardEffect.AMOUNT_OFF_UNIT) != (request.amount() != null)) {
            throw LoyaltyRuleException.programInvalid("Only an AMOUNT_OFF_UNIT reward has an amount, and it needs one.");
        }
        if (request.amount() != null && request.amount().signum() <= 0) {
            throw LoyaltyRuleException.programInvalid("The amount off a unit is positive.");
        }
        if (request.itemId() != null && request.familyId() != null) {
            throw LoyaltyRuleException.programInvalid("A reward is scoped to an item or a family, not both.");
        }
    }

    private static Money amount(RewardRequest request, Currency currency) {
        return request.amount() == null ? null : money(request.amount(), currency);
    }

    private static Money money(BigDecimal amount, Currency currency) {
        try {
            return Money.of(amount, currency);
        } catch (IllegalArgumentException e) {
            throw LoyaltyRuleException.programInvalid("The amount " + amount.toPlainString()
                    + " has more decimals than " + currency.getCurrencyCode() + " allows.");
        }
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private void audit(String action, LoyaltyProgramVersion version) {
        auditLog.recordAsCurrentActor(action, LoyaltyProgramVersion.ENTITY, version.getId().toString(), null, null,
                null, Map.of("versionNo", version.getVersionNo(),
                        "spendUnit", version.spendUnit().amount().toPlainString(),
                        "pointsPerUnit", version.getPointsPerUnit()));
    }

    private void auditReward(String action, LoyaltyReward reward) {
        auditLog.recordAsCurrentActor(action, LoyaltyReward.ENTITY, reward.getId().toString(), null, null, null,
                Map.of("versionId", reward.getVersionId().toString(), "code", reward.getCode(),
                        "pointsCost", reward.getPointsCost(), "effect", reward.getEffect().name()));
    }
}
