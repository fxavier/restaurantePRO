package com.xavier.backend.catalog;

import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.NoPublishedCatalogException;
import com.xavier.backend.catalog.model.ConfigurationRelease;
import com.xavier.backend.catalog.model.DailyDish;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemVariant;
import com.xavier.backend.catalog.model.ReleaseStatus;
import com.xavier.backend.catalog.model.SoldOutFlag;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.catalog.repository.ConfigurationReleaseRepository;
import com.xavier.backend.catalog.repository.DailyDishRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.SoldOutFlagRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * What other modules may ask the catalog (spec §8.2): the frozen catalog of a store as a typed
 * {@link CatalogSnapshot}, parsed from the stored jsonb once per call. Orders (S13) snapshot name, price and tax
 * from it at line confirmation (D58), and the working-copy item references recipes name (D100). Read-only and without
 * authorization: callers authorize their own use case.
 */
@Service
@Transactional(readOnly = true)
public class CatalogApi {

    private final ConfigurationReleaseRepository releases;
    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final SoldOutFlagRepository soldOutFlags;
    private final DailyDishRepository dailyDishes;
    private final JsonMapper json;

    CatalogApi(ConfigurationReleaseRepository releases, ItemRepository items, ItemVariantRepository variants,
               SoldOutFlagRepository soldOutFlags, DailyDishRepository dailyDishes, JsonMapper json) {
        this.releases = releases;
        this.items = items;
        this.variants = variants;
        this.soldOutFlags = soldOutFlags;
        this.dailyDishes = dailyDishes;
        this.json = json;
    }

    /**
     * The catalog the store sells from now.
     *
     * @throws NoPublishedCatalogException (404) when the store has never published
     */
    public CatalogSnapshot currentSnapshot(UUID storeId) {
        return parse(releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED)
                .orElseThrow(() -> new NoPublishedCatalogException(storeId)));
    }

    /**
     * The catalog the store sells from now, or empty when it has never published. Never throws, so a caller's
     * transaction is never marked for rollback by a missing catalog (orders opening an order, D109).
     */
    public Optional<CatalogSnapshot> findCurrentSnapshot(UUID storeId) {
        return releases.findByStoreIdAndStatus(storeId, ReleaseStatus.PUBLISHED).map(this::parse);
    }

    /**
     * A frozen release of the store, current or retired: an order line confirmed under it keeps reading it.
     *
     * @throws CatalogEntryNotFoundException (404) when the release does not exist, belongs to another store, or
     *                                       was never published
     */
    public CatalogSnapshot snapshot(UUID storeId, UUID releaseId) {
        return parse(releases.findById(releaseId)
                .filter(release -> release.getStoreId().equals(storeId) && release.getSnapshot() != null)
                .orElseThrow(() -> new CatalogEntryNotFoundException("Release", releaseId)));
    }

    /** The working-copy items among {@code ids}, active or not, ordered by code; an unknown id is left out. */
    public List<ItemRef> items(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<UUID>> variantIds = variants.findAllByItemIdIn(ids).stream()
                .sorted(Comparator.comparing(ItemVariant::getCode))
                .collect(Collectors.groupingBy(ItemVariant::getItemId,
                        Collectors.mapping(ItemVariant::getId, Collectors.toList())));
        return items.findAllById(ids).stream()
                .sorted(Comparator.comparing(Item::getCode))
                .map(item -> new ItemRef(item.getId(), item.getCode(), item.getName(), item.getFamilyId(),
                        item.isActive(), variantIds.getOrDefault(item.getId(), List.of())))
                .toList();
    }

    /**
     * The sold-out keys of the store now (D109, operational: outside any release). An item-level key
     * ({@code variantId} null) covers every variant of the item.
     */
    public Set<ItemKey> soldOut(UUID storeId) {
        return soldOutFlags.findAllByStoreIdOrderBySoldOutAtAscIdAsc(storeId).stream().map(SoldOutFlag::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** The daily dishes of the store for {@code businessDate}, in order (D109); empty when none were set. */
    public List<UUID> dailyDishes(UUID storeId, LocalDate businessDate) {
        return dailyDishes.findAllByIdStoreIdAndIdBusinessDateOrderByPosition(storeId, businessDate).stream()
                .map(DailyDish::getItemId).toList();
    }

    private CatalogSnapshot parse(ConfigurationRelease release) {
        return json.readValue(release.getSnapshot(), CatalogSnapshot.class);
    }
}
