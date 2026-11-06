package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogRuleException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.DailyDish;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.model.dto.DailyDishesRequest;
import com.xavier.backend.catalog.model.dto.DailyDishesView;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import com.xavier.backend.catalog.repository.DailyDishRepository;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.organization.model.dto.StoreSummary;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daily dishes (D109): per store × business date, operational, outside the release. Setting them needs
 * {@code CATALOG_AVAILABILITY} in the store and replaces the day's list; every dish must be an item of the store's
 * published release (422 {@code ITEM_NOT_PUBLISHED}).
 */
@Service
public class DailyDishService {

    private final DailyDishRepository dishes;
    private final ConfigurationReleaseRepository releases;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    DailyDishService(DailyDishRepository dishes, ConfigurationReleaseRepository releases,
                     OrganizationApi organization, AccessPolicy accessPolicy, CatalogAudit audit, JsonMapper json,
                     Clock clock) {
        this.dishes = dishes;
        this.releases = releases;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public DailyDishesView set(DailyDishesRequest request) {
        UUID storeId = request.storeId();
        accessPolicy.require(Permission.CATALOG_AVAILABILITY, storeId);
        List<UUID> itemIds = request.itemIds();
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw new InvalidCatalogEntryException("A daily dish is listed twice");
        }
        Set<UUID> published = publishedItems(storeId);
        List<UUID> unpublished = itemIds.stream().filter(id -> !published.contains(id)).toList();
        if (!unpublished.isEmpty()) {
            throw CatalogRuleException.itemNotPublished(storeId, unpublished);
        }
        LocalDate day = request.businessDate();
        dishes.lockDay(storeId.toString(), day.toString());
        dishes.clear(storeId, day);
        for (int position = 0; position < itemIds.size(); position++) {
            dishes.add(storeId, day, itemIds.get(position), position);
        }
        audit.record("DAILY_DISHES_SET", "DailyDishes", storeId, storeId, Map.of("businessDate", day.toString(),
                "itemIds", itemIds.stream().map(UUID::toString).toList()));
        return new DailyDishesView(storeId, day, List.copyOf(itemIds));
    }

    /** The POS session's store ({@code CATALOG_VIEW}); {@code date} {@code null} = the store's business day now. */
    @Transactional(readOnly = true)
    public DailyDishesView forPos(LocalDate date) {
        UUID storeId = accessPolicy.requirePos(Permission.CATALOG_VIEW).storeId();
        LocalDate day = date != null ? date : today(storeId);
        return new DailyDishesView(storeId, day, list(storeId, day));
    }

    private List<UUID> list(UUID storeId, LocalDate businessDate) {
        return dishes.findAllByIdStoreIdAndIdBusinessDateOrderByPosition(storeId, businessDate).stream()
                .map(DailyDish::getItemId).toList();
    }

    private Set<UUID> publishedItems(UUID storeId) {
        return releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .map(ConfigurationRelease::getSnapshot)
                .map(snapshot -> json.readValue(snapshot, CatalogSnapshot.class).items().stream()
                        .map(CatalogSnapshot.Item::id).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private LocalDate today(UUID storeId) {
        StoreSummary store = organization.findStore(storeId).orElseThrow();
        return BusinessDays.of(clock.instant(), store.timezone(), store.businessDayCutoff());
    }
}
