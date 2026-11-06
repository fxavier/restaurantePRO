package com.xavier.backend.promotions.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.promotions.exception.PromotionConflictException;
import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.exception.PromotionsNotFoundException;
import com.xavier.backend.promotions.model.Promotion;
import com.xavier.backend.promotions.model.PromotionKind;
import com.xavier.backend.promotions.model.dto.PromotionCreateRequest;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionUpdateRequest;
import com.xavier.backend.promotions.model.dto.PromotionView;
import com.xavier.backend.promotions.model.dto.ScopeRequest;
import com.xavier.backend.promotions.model.dto.StoresRequest;
import com.xavier.backend.promotions.model.dto.WindowsRequest;
import com.xavier.backend.promotions.repository.PromotionRepository;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The promotions working copy (D102). Promotions are company-wide: editing needs {@code PROMOTION_MANAGE} in some
 * store, reading {@code PROMOTION_VIEW}; assigning or removing a store also needs {@code PROMOTION_MANAGE} in that
 * store. Nothing here changes a price until the store publishes a promotion release.
 */
@Service
public class PromotionService {

    private final PromotionRepository promotions;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final PromotionsAudit audit;
    private final Clock clock;

    PromotionService(PromotionRepository promotions, OrganizationApi organization, AccessPolicy accessPolicy,
                     PromotionsAudit audit, Clock clock) {
        this.promotions = promotions;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PromotionView create(PromotionCreateRequest request) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Promotion promotion = Promotion.create(request.code(), new Promotion.Terms(request.name(),
                PromotionKind.valueOf(request.kind()), request.percent(), money(request.amount()), request.priority(),
                request.validFrom(), request.validTo(), Boolean.TRUE.equals(request.membersOnly()), request.modes(),
                request.active() == null || request.active()), clock.instant());
        UniqueConstraints.saveAndFlush(promotions, promotion,
                () -> PromotionConflictException.codeTaken(request.code().strip()), "promotion_code_uk");
        audit.record("PROMOTION_CREATED", "Promotion", promotion.getId(), null, Map.of("code", promotion.getCode()));
        return promotion.toView();
    }

    @Transactional
    public PromotionView update(UUID id, PromotionUpdateRequest request) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Promotion promotion = editable(id, request.version());
        promotion.change(new Promotion.Terms(request.name(), PromotionKind.valueOf(request.kind()), request.percent(),
                money(request.amount()), request.priority(), request.validFrom(), request.validTo(),
                request.membersOnly(), request.modes(), request.active()), clock.instant());
        return saved(promotion, "PROMOTION_UPDATED");
    }

    @Transactional
    public PromotionView replaceScope(UUID id, ScopeRequest request) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Promotion promotion = editable(id, request.version());
        promotion.replaceScope(orEmpty(request.itemIds()), orEmpty(request.variantIds()),
                orEmpty(request.familyIds()), clock.instant());
        return saved(promotion, "PROMOTION_SCOPE_CHANGED");
    }

    @Transactional
    public PromotionView replaceWindows(UUID id, WindowsRequest request) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Promotion promotion = editable(id, request.version());
        promotion.replaceWindows(request.windows().stream()
                .map(window -> new PromotionSnapshot.Window(window.daysOfWeek(), window.start(), window.end()))
                .toList(), clock.instant());
        return saved(promotion, "PROMOTION_WINDOWS_CHANGED");
    }

    /** Every store added or removed needs {@code PROMOTION_MANAGE} there; an added store must exist. */
    @Transactional
    public PromotionView replaceStores(UUID id, StoresRequest request) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Set<UUID> requested = new LinkedHashSet<>(request.storeIds());
        Promotion promotion = editable(id, request.version());
        Set<UUID> changed = new HashSet<>(requested);
        changed.addAll(promotion.getStoreIds());
        changed.removeIf(store -> requested.contains(store) && promotion.getStoreIds().contains(store));
        changed.stream().sorted().forEach(store -> accessPolicy.require(Permission.PROMOTION_MANAGE, store));
        requested.stream().filter(store -> !organization.storeExists(store)).findFirst().ifPresent(store -> {
            throw PromotionRuleException.invalid("Store " + store + " does not exist.");
        });
        promotion.replaceStores(requested, clock.instant());
        return saved(promotion, "PROMOTION_STORES_CHANGED");
    }

    /** Removes a working-copy promotion; releases already published keep their frozen copy of it. */
    @Transactional
    public void delete(UUID id, long version) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_MANAGE);
        Promotion promotion = editable(id, version);
        promotions.delete(promotion);
        promotions.flush();
        audit.record("PROMOTION_DELETED", "Promotion", id, null, Map.of("code", promotion.getCode()));
    }

    @Transactional(readOnly = true)
    public PromotionView get(UUID id) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_VIEW);
        return promotion(id).toView();
    }

    @Transactional(readOnly = true)
    public PageResponse<PromotionView> list(Pageable pageable) {
        accessPolicy.requireInAnyStore(Permission.PROMOTION_VIEW);
        return PageResponse.from(promotions.findAll(pageable).map(Promotion::toView));
    }

    private Promotion editable(UUID id, long version) {
        Promotion promotion = promotion(id);
        OptimisticLocking.requireVersion(Promotion.class, id, promotion.getVersion(), version);
        return promotion;
    }

    private PromotionView saved(Promotion promotion, String action) {
        promotions.saveAndFlush(promotion);
        audit.record(action, "Promotion", promotion.getId(), null, Map.of("code", promotion.getCode()));
        return promotion.toView();
    }

    private Promotion promotion(UUID id) {
        return promotions.findById(id).orElseThrow(() -> PromotionsNotFoundException.promotion(id));
    }

    /** An amount in the company currency; more decimals than the currency allows is a 422. */
    private Money money(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        try {
            return Money.of(amount, organization.companyProfile().currency());
        } catch (IllegalArgumentException e) {
            throw PromotionRuleException.invalid("The amount " + amount.toPlainString() + " has more decimals than "
                    + organization.companyProfile().currency() + " allows.");
        }
    }

    private static List<UUID> orEmpty(List<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).toList();
    }
}
