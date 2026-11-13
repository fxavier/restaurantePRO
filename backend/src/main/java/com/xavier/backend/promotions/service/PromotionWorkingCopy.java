package com.xavier.backend.promotions.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.promotions.model.Promotion;
import com.xavier.backend.promotions.model.StoreDiscountLimits;
import com.xavier.backend.promotions.model.dto.DiscountLimits;
import com.xavier.backend.promotions.model.dto.PromotionReleaseError;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.repository.PromotionRepository;
import com.xavier.backend.promotions.repository.StoreDiscountLimitsRepository;
import com.xavier.backend.shared.exception.NotFoundException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * What a store's next promotion release would freeze, and every problem that blocks it (D102): its ACTIVE promotions
 * with their scope (a family stands for itself and its subfamilies), and its discount limits (the P44 defaults when it
 * has none). Each scope entry must name an item,
 * variant or family of the store's published catalog, and amounts must be in the catalog's currency.
 *
 * <p>D147 (amended 2026-11-13, S58 review): a scope item, or a variant of it, that the published catalog lacks because
 * the store excludes the item does not apply in the store: it is left out of the frozen scope with a
 * {@code PROMOTION_SCOPE_ITEM_EXCLUDED} warning, which never blocks. Anything else the catalog lacks is an error.
 */
@Component
class PromotionWorkingCopy {

    static final String NO_PUBLISHED_CATALOG = "NO_PUBLISHED_CATALOG";
    static final String SCOPE_NOT_IN_CATALOG = "SCOPE_NOT_IN_CATALOG";
    static final String PROMOTION_INVALID = "PROMOTION_INVALID";
    static final String PROMOTION_SCOPE_ITEM_EXCLUDED = "PROMOTION_SCOPE_ITEM_EXCLUDED";

    private final PromotionRepository promotions;
    private final StoreDiscountLimitsRepository limits;
    private final CatalogApi catalog;

    PromotionWorkingCopy(PromotionRepository promotions, StoreDiscountLimitsRepository limits, CatalogApi catalog) {
        this.promotions = promotions;
        this.limits = limits;
        this.catalog = catalog;
    }

    /**
     * @param subfamilies  each family of the published catalog to its direct subfamilies
     * @param notApplying  scope items and variants the store excludes and its catalog lacks: left out of every scope
     * @param warnings     one {@code PROMOTION_SCOPE_ITEM_EXCLUDED} per such scope entry; never blocking
     */
    record View(UUID storeId, String currency, DiscountLimits discountLimits, List<Promotion> promotions,
                Map<UUID, List<UUID>> subfamilies, Set<UUID> notApplying, List<PromotionReleaseError> errors,
                List<PromotionReleaseError> warnings) {

        /**
         * The frozen promotions, each family in a scope expanded to that family and all its descendants in the
         * published catalog, so the engine matches a line's own family exactly.
         */
        PromotionSnapshot snapshot(UUID releaseId, long version) {
            return new PromotionSnapshot(PromotionSnapshot.SCHEMA_VERSION, storeId, releaseId, version, currency,
                    discountLimits, promotions.stream().map(Promotion::toSnapshot).map(this::withSubfamilies).toList());
        }

        private PromotionSnapshot.Promotion withSubfamilies(PromotionSnapshot.Promotion p) {
            Set<UUID> families = new LinkedHashSet<>();
            Deque<UUID> pending = new ArrayDeque<>(p.scope().familyIds());
            while (!pending.isEmpty()) {
                UUID family = pending.removeFirst();
                if (families.add(family)) {
                    pending.addAll(subfamilies.getOrDefault(family, List.of()));
                }
            }
            PromotionSnapshot.Scope scope = new PromotionSnapshot.Scope(applying(p.scope().itemIds()),
                    applying(p.scope().variantIds()), List.copyOf(families));
            return new PromotionSnapshot.Promotion(p.id(), p.code(), p.name(), p.kind(), p.percent(), p.amount(),
                    p.priority(), p.validFrom(), p.validTo(), p.membersOnly(), p.modes(), scope, p.windows());
        }

        private List<UUID> applying(List<UUID> ids) {
            return ids.stream().filter(id -> !notApplying.contains(id)).toList();
        }
    }

    View of(UUID storeId) {
        List<Promotion> active = promotions.findActiveForStore(storeId);
        DiscountLimits discountLimits = limits.findById(storeId).map(StoreDiscountLimits::toLimits)
                .orElse(StoreDiscountLimits.DEFAULTS);
        Optional<CatalogSnapshot> published = publishedCatalog(storeId);
        if (published.isEmpty()) {
            return new View(storeId, null, discountLimits, active, Map.of(), Set.of(),
                    List.of(new PromotionReleaseError(NO_PUBLISHED_CATALOG, null, null,
                            "Store " + storeId + " has no published catalog.")), List.of());
        }
        CatalogSnapshot sold = published.get();
        Set<UUID> items = sold.items().stream().map(CatalogSnapshot.Item::id).collect(Collectors.toSet());
        Set<UUID> variants = sold.items().stream().flatMap(item -> item.variants().stream())
                .map(CatalogSnapshot.Variant::id).collect(Collectors.toSet());
        Set<UUID> families = sold.families().stream().map(CatalogSnapshot.Family::id).collect(Collectors.toSet());
        Set<UUID> excluded = excludedItemsAndVariants(storeId);
        Set<UUID> notApplying = new HashSet<>();
        List<PromotionReleaseError> errors = new ArrayList<>();
        List<PromotionReleaseError> warnings = new ArrayList<>();
        for (Promotion promotion : active) {
            if (promotion.hasEmptyScope()) {
                errors.add(error(PROMOTION_INVALID, promotion, "Promotion " + promotion.getCode()
                        + " has an empty scope."));
                continue;
            }
            if (promotion.getAmount() != null
                    && !promotion.getAmount().currency().getCurrencyCode().equals(sold.currency())) {
                errors.add(error(PROMOTION_INVALID, promotion, "Promotion " + promotion.getCode() + " is in "
                        + promotion.getAmount().currency() + ", the catalog in " + sold.currency() + "."));
            }
            PromotionSnapshot.Scope scope = promotion.toScope();
            List<UUID> missing = Stream.of(
                            scope.itemIds().stream().filter(id -> !items.contains(id)),
                            scope.variantIds().stream().filter(id -> !variants.contains(id)),
                            scope.familyIds().stream().filter(id -> !families.contains(id)))
                    .flatMap(ids -> ids).toList();
            for (UUID id : missing.stream().filter(excluded::contains).toList()) {
                notApplying.add(id);
                warnings.add(error(PROMOTION_SCOPE_ITEM_EXCLUDED, promotion, "Promotion " + promotion.getCode()
                        + " does not apply to " + id + " in this store, which excludes the item."));
            }
            missing = missing.stream().filter(id -> !excluded.contains(id)).toList();
            if (!missing.isEmpty()) {
                errors.add(error(SCOPE_NOT_IN_CATALOG, promotion, "Promotion " + promotion.getCode()
                        + " names what the published catalog of the store does not sell: " + missing + "."));
            }
        }
        Map<UUID, List<UUID>> subfamilies = sold.families().stream().filter(family -> family.parentId() != null)
                .collect(Collectors.groupingBy(CatalogSnapshot.Family::parentId,
                        Collectors.mapping(CatalogSnapshot.Family::id, Collectors.toList())));
        return new View(storeId, sold.currency(), discountLimits, active, subfamilies, Set.copyOf(notApplying),
                errors, warnings);
    }

    /** The items the store excludes and their variants, read through the catalog's API. */
    private Set<UUID> excludedItemsAndVariants(UUID storeId) {
        Set<UUID> items = catalog.excludedItems(storeId);
        if (items.isEmpty()) {
            return Set.of();
        }
        return Stream.concat(items.stream(), catalog.items(items).stream().flatMap(item -> item.variantIds().stream()))
                .collect(Collectors.toSet());
    }

    private Optional<CatalogSnapshot> publishedCatalog(UUID storeId) {
        try {
            return Optional.of(catalog.currentSnapshot(storeId));
        } catch (NotFoundException noPublishedCatalog) {
            return Optional.empty();
        }
    }

    private static PromotionReleaseError error(String code, Promotion promotion, String message) {
        return new PromotionReleaseError(code, promotion.getId(), promotion.getCode(), message);
    }
}
