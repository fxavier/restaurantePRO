package com.xavier.backend.promotions.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.model.StoreDiscountLimits;
import com.xavier.backend.promotions.model.dto.DiscountLimitsRequest;
import com.xavier.backend.promotions.model.dto.DiscountLimitsView;
import com.xavier.backend.promotions.repository.StoreDiscountLimitsRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A store's manual discount limits in the working copy (D106, P44), frozen by its next promotion release. Setting them
 * needs {@code PROMOTION_MANAGE} in the store, reading {@code PROMOTION_VIEW} there.
 */
@Service
public class DiscountLimitsService {

    /** The version a client sends (as null) and a store has before it sets its own limits. */
    private static final long NONE = -1;

    private final StoreDiscountLimitsRepository limits;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final PromotionsAudit audit;
    private final Clock clock;

    DiscountLimitsService(StoreDiscountLimitsRepository limits, OrganizationApi organization,
                          AccessPolicy accessPolicy, PromotionsAudit audit, Clock clock) {
        this.limits = limits;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Sets the limits. The first time {@code version} is null; afterwards it is the version the client read (409 when
     * stale, or when another first setting won).
     */
    @Transactional
    public DiscountLimitsView put(UUID storeId, DiscountLimitsRequest request) {
        accessPolicy.require(Permission.PROMOTION_MANAGE, storeId);
        if (!organization.storeExists(storeId)) {
            throw PromotionRuleException.invalid("Store " + storeId + " does not exist.");
        }
        Optional<StoreDiscountLimits> existing = limits.findById(storeId);
        long stored = existing.map(StoreDiscountLimits::getVersion).orElse(NONE);
        OptimisticLocking.requireVersion(StoreDiscountLimits.class, storeId, stored,
                request.version() == null ? NONE : request.version());
        StoreDiscountLimits saved;
        if (existing.isPresent()) {
            saved = existing.get();
            saved.change(request.freeLimitPercent(), request.maxPercent(), clock.instant());
            limits.saveAndFlush(saved);
        } else {
            saved = UniqueConstraints.saveAndFlush(limits,
                    StoreDiscountLimits.of(storeId, request.freeLimitPercent(), request.maxPercent(), clock.instant()),
                    () -> new OptimisticLockingFailureException(
                            "The discount limits of store " + storeId + " were set at the same time"),
                    "discount_limits_pk");
        }
        DiscountLimitsView view = saved.toView();
        audit.record("PROMOTION_DISCOUNT_LIMITS_SET", "DiscountLimits", storeId, storeId,
                Map.of("freeLimitPercent", view.freeLimitPercent(), "maxPercent", view.maxPercent()));
        return view;
    }

    /** The store's working-copy limits, or the P44 defaults with a null version. */
    @Transactional(readOnly = true)
    public DiscountLimitsView get(UUID storeId) {
        accessPolicy.require(Permission.PROMOTION_VIEW, storeId);
        return limits.findById(storeId).map(StoreDiscountLimits::toView)
                .orElseGet(() -> new DiscountLimitsView(storeId, StoreDiscountLimits.DEFAULTS.freeLimitPercent(),
                        StoreDiscountLimits.DEFAULTS.maxPercent(), null));
    }
}
