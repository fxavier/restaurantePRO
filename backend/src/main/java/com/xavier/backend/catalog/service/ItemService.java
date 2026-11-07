package com.xavier.backend.catalog.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.exception.CatalogCodeTakenException;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemAvailability;
import com.xavier.backend.catalog.model.ItemVariant;
import com.xavier.backend.catalog.model.dto.ItemCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ItemSummary;
import com.xavier.backend.catalog.model.dto.ItemUpdateRequest;
import com.xavier.backend.catalog.model.dto.ItemVariantCreateRequest;
import com.xavier.backend.catalog.model.dto.ItemVariantSummary;
import com.xavier.backend.catalog.model.dto.ItemVariantUpdateRequest;
import com.xavier.backend.catalog.repository.FamilyRepository;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import com.xavier.backend.catalog.repository.TaxCategoryRepository;
import com.xavier.backend.catalog.repository.UnitRepository;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Items and their variants are company-wide: installation-wide {@code CATALOG_MANAGE}. */
@Service
public class ItemService {

    private final ItemRepository items;
    private final ItemVariantRepository variants;
    private final FamilyRepository families;
    private final UnitRepository units;
    private final TaxCategoryRepository taxCategories;
    private final AccessPolicy accessPolicy;
    private final CatalogAudit audit;
    private final Clock clock;

    ItemService(ItemRepository items, ItemVariantRepository variants, FamilyRepository families,
                UnitRepository units, TaxCategoryRepository taxCategories, AccessPolicy accessPolicy,
                CatalogAudit audit, Clock clock) {
        this.items = items;
        this.variants = variants;
        this.families = families;
        this.units = units;
        this.taxCategories = taxCategories;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ItemSummary create(ItemCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Item.Details details = new Item.Details(request.code(), request.name(), request.familyId(), request.unitId(),
                request.taxCategoryId(), request.productionDestination(), request.soldByWeight());
        requireReferences(details);
        Item item = save(Item.create(details, kind(request.kind()), clock.instant()));
        audit("ITEM_CREATED", item);
        return item.toSummary();
    }

    @Transactional
    public ItemSummary update(UUID id, ItemUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Item item = item(id);
        OptimisticLocking.requireVersion(Item.class, id, item.getVersion(), request.version());
        Item.Details details = new Item.Details(request.code(), request.name(), request.familyId(), request.unitId(),
                request.taxCategoryId(), request.productionDestination(), request.soldByWeight());
        requireReferences(details);
        item.update(details, ItemAvailability.valueOf(request.availability()), kind(request.kind()), clock.instant());
        save(item);
        audit("ITEM_UPDATED", item);
        return item.toSummary();
    }

    @Transactional(readOnly = true)
    public ItemSummary get(UUID id) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return item(id).toSummary();
    }

    @Transactional(readOnly = true)
    public PageResponse<ItemSummary> list(Pageable pageable) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return PageResponse.from(items.findAll(pageable).map(Item::toSummary));
    }

    @Transactional
    public ItemVariantSummary createVariant(UUID itemId, ItemVariantCreateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        Item item = item(itemId);
        ItemVariant variant = save(ItemVariant.create(item.getId(), request.code(), request.name(), clock.instant()));
        auditVariant("ITEM_VARIANT_CREATED", variant);
        return variant.toSummary();
    }

    @Transactional
    public ItemVariantSummary updateVariant(UUID itemId, UUID variantId, ItemVariantUpdateRequest request) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        ItemVariant variant = variant(itemId, variantId);
        OptimisticLocking.requireVersion(ItemVariant.class, variantId, variant.getVersion(), request.version());
        variant.update(request.code(), request.name(), clock.instant());
        save(variant);
        auditVariant("ITEM_VARIANT_UPDATED", variant);
        return variant.toSummary();
    }

    @Transactional(readOnly = true)
    public ItemVariantSummary getVariant(UUID itemId, UUID variantId) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        return variant(itemId, variantId).toSummary();
    }

    @Transactional(readOnly = true)
    public List<ItemVariantSummary> listVariants(UUID itemId) {
        accessPolicy.require(Permission.CATALOG_MANAGE);
        item(itemId);
        return variants.findAllByItemIdOrderByCode(itemId).stream().map(ItemVariant::toSummary).toList();
    }

    private static ItemKind kind(String kind) {
        return kind == null ? null : ItemKind.valueOf(kind);
    }

    private void requireReferences(Item.Details details) {
        if (!families.existsById(details.familyId())) {
            throw new InvalidCatalogEntryException("Family " + details.familyId() + " does not exist");
        }
        if (!units.existsById(details.unitId())) {
            throw new InvalidCatalogEntryException("Unit " + details.unitId() + " does not exist");
        }
        if (!taxCategories.existsById(details.taxCategoryId())) {
            throw new InvalidCatalogEntryException("Tax category " + details.taxCategoryId() + " does not exist");
        }
    }

    private Item save(Item item) {
        return UniqueConstraints.saveAndFlush(items, item,
                () -> new CatalogCodeTakenException("Item code '" + item.getCode() + "'"), "item_code_uk");
    }

    private ItemVariant save(ItemVariant variant) {
        return UniqueConstraints.saveAndFlush(variants, variant,
                () -> new CatalogCodeTakenException("Variant code '" + variant.getCode() + "' in this item"),
                "item_variant_item_code_uk");
    }

    private Item item(UUID id) {
        return items.findById(id).orElseThrow(() -> new CatalogEntryNotFoundException("Item", id));
    }

    private ItemVariant variant(UUID itemId, UUID variantId) {
        return variants.findByIdAndItemId(variantId, itemId)
                .orElseThrow(() -> new CatalogEntryNotFoundException("Item variant", variantId));
    }

    private void audit(String action, Item item) {
        audit.record(action, "Item", item.getId(), null, Map.of("code", item.getCode(), "availability",
                item.getAvailability().name(), "kind", item.getKind().name()));
    }

    private void auditVariant(String action, ItemVariant variant) {
        audit.record(action, "ItemVariant", variant.getId(), null,
                Map.of("itemId", variant.getItemId().toString(), "code", variant.getCode()));
    }
}
