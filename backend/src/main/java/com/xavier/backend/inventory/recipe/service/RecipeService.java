package com.xavier.backend.inventory.recipe.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.ItemRef;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.Dimension;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.StockItemKind;
import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import com.xavier.backend.inventory.recipe.model.IngredientLine;
import com.xavier.backend.inventory.recipe.model.Recipe;
import com.xavier.backend.inventory.recipe.model.RecipeVersion;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import com.xavier.backend.inventory.recipe.model.dto.ActivateRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeUpdateRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionRequest;
import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionView;
import com.xavier.backend.inventory.recipe.model.dto.RecipeView;
import com.xavier.backend.inventory.recipe.repository.RecipeRepository;
import com.xavier.backend.inventory.recipe.repository.RecipeVersionRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.service.Units;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recipes and their versions (D115), company-wide: writes need {@code RECIPE_MANAGE} installation-wide, reads hold
 * it installation-wide or in any store.
 * A version is edited while DRAFT and immutable once activated; activating one supersedes the ACTIVE version at the
 * new start, under the recipe's row lock, so periods never overlap. Every write is audited.
 */
@Service
public class RecipeService {

    static final String ENTITY = "Recipe";

    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;
    private final StockItemRepository stockItems;
    private final Units units;
    private final CatalogApi catalog;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    RecipeService(RecipeRepository recipes, RecipeVersionRepository versions, StockItemRepository stockItems,
                  Units units, CatalogApi catalog, AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.recipes = recipes;
        this.versions = versions;
        this.stockItems = stockItems;
        this.units = units;
        this.catalog = catalog;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<RecipeView> list(Pageable pageable) {
        accessPolicy.requireInAnyStore(Permission.RECIPE_MANAGE);
        return PageResponse.from(recipes.findAllByOrderByNameAscIdAsc(pageable).map(r -> r.toView(List.of())));
    }

    @Transactional(readOnly = true)
    public RecipeView get(UUID id) {
        accessPolicy.requireInAnyStore(Permission.RECIPE_MANAGE);
        return view(require(id));
    }

    @Transactional
    public RecipeView create(RecipeRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = request.outputStockItemId() != null ? preparation(request) : sale(request);
        Recipe saved = UniqueConstraints.saveAndFlush(recipes, recipe, () -> RecipeConflictException.exists(
                        recipe.isPreparation() ? "stock item " + recipe.getOutputStockItemId()
                                : "item " + recipe.getItemId() + (recipe.getVariantId() == null ? ""
                                : " variant " + recipe.getVariantId())),
                "recipe_sale_uk", "recipe_preparation_uk");
        Map<String, Object> details = new HashMap<>();
        details.put("name", saved.getName());
        details.put("target", saved.isPreparation() ? saved.getOutputStockItemId().toString()
                : saved.getItemId().toString());
        auditLog.recordAsCurrentActor("RECIPE_CREATED", ENTITY, saved.getId().toString(), null, null, null, details);
        return saved.toView(List.of());
    }

    @Transactional
    public RecipeView rename(UUID id, RecipeUpdateRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = require(id);
        OptimisticLocking.requireVersion(Recipe.class, id, recipe.getVersion(), request.version());
        recipe.rename(request.name());
        recipes.flush();
        auditLog.recordAsCurrentActor("RECIPE_RENAMED", ENTITY, id.toString(), null, null, null,
                Map.of("name", recipe.getName()));
        return view(recipe);
    }

    /**
     * Deletes a recipe that was never used: every version still DRAFT (409 RECIPE_VERSION_IMMUTABLE otherwise, a
     * used recipe is a fact; it is superseded instead).
     */
    @Transactional
    public void delete(UUID id) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = recipes.lockById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        List<RecipeVersion> all = versions.findByRecipeIdOrderByVersionNo(id);
        all.stream().filter(v -> v.getStatus() != VersionStatus.DRAFT).findFirst().ifPresent(used -> {
            throw RecipeConflictException.immutable(used.getId(), used.getStatus(), "be deleted");
        });
        versions.deleteAll(all);
        recipes.delete(recipe);
        recipes.flush();
        auditLog.recordAsCurrentActor("RECIPE_DELETED", ENTITY, id.toString(), null, null, null,
                Map.of("name", recipe.getName()));
    }

    /** A new DRAFT version, numbered after the last one (under the recipe's lock). */
    @Transactional
    public RecipeVersionView addVersion(UUID recipeId, RecipeVersionRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = recipes.lockById(recipeId)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, recipeId));
        List<IngredientLine> lines = validated(recipe, request);
        RecipeVersion version = versions.saveAndFlush(RecipeVersion.draft(recipeId,
                versions.maxVersionNo(recipeId) + 1, request.yieldQuantity(), request.yieldUnit(), request.notes(),
                lines, accessPolicy.current().userId(), clock.instant()));
        auditLog.recordAsCurrentActor("RECIPE_VERSION_CREATED", ENTITY, recipeId.toString(), null, null, null,
                Map.of("versionNo", version.getVersionNo()));
        return version.toView();
    }

    /** Rewrites a DRAFT (409 RECIPE_VERSION_IMMUTABLE otherwise). */
    @Transactional
    public RecipeVersionView reviseVersion(UUID recipeId, int versionNo, RecipeVersionRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = require(recipeId);
        RecipeVersion version = requireVersion(recipeId, versionNo);
        if (request.version() != null) {
            OptimisticLocking.requireVersion(RecipeVersion.class, version.getId(), version.getVersion(),
                    request.version());
        }
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw RecipeConflictException.immutable(version.getId(), version.getStatus(), "be edited");
        }
        version.revise(request.yieldQuantity(), request.yieldUnit(), request.notes(), validated(recipe, request));
        versions.flush();
        auditLog.recordAsCurrentActor("RECIPE_VERSION_REVISED", ENTITY, recipeId.toString(), null, null, null,
                Map.of("versionNo", versionNo));
        return version.toView();
    }

    /**
     * DRAFT → ACTIVE from {@code effectiveFrom} (now when absent, never earlier than now), superseding the ACTIVE
     * version at that instant.
     *
     * @throws RecipeRuleException RECIPE_EFFECTIVE_FROM_INVALID in the past or not after the current version's start
     */
    @Transactional
    public RecipeVersionView activate(UUID recipeId, int versionNo, ActivateRequest request) {
        accessPolicy.require(Permission.RECIPE_MANAGE);
        Recipe recipe = recipes.lockById(recipeId)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, recipeId));
        RecipeVersion version = requireVersion(recipeId, versionNo);
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw RecipeConflictException.immutable(version.getId(), version.getStatus(), "be activated");
        }
        Instant now = clock.instant();
        Instant from = request == null || request.effectiveFrom() == null ? now : request.effectiveFrom();
        if (from.isBefore(now)) {
            throw RecipeRuleException.effectiveFromInPast(now);
        }
        if (recipe.isPreparation()) {
            requireNoCycle(recipe.getOutputStockItemId(), version.getIngredients().stream()
                    .map(i -> i.getStockItemId()).collect(Collectors.toSet()));
        }
        Optional<RecipeVersion> current = versions.findByRecipeIdAndPeriodStatus(recipeId, VersionStatus.ACTIVE);
        current.ifPresent(active -> {
            active.supersedeAt(from);
            versions.flush(); // the partial unique index allows one ACTIVE version: retire it first
        });
        version.activate(from, accessPolicy.current().userId(), now);
        versions.flush();
        Map<String, Object> details = new HashMap<>();
        details.put("versionNo", versionNo);
        details.put("effectiveFrom", from.toString());
        current.ifPresent(active -> details.put("supersedes", active.getVersionNo()));
        auditLog.recordAsCurrentActor("RECIPE_VERSION_ACTIVATED", ENTITY, recipeId.toString(), null, null, null,
                details);
        return version.toView();
    }

    private Recipe sale(RecipeRequest request) {
        if (request.itemId() == null) {
            throw RecipeRuleException.targetInvalid();
        }
        ItemRef item = catalog.items(List.of(request.itemId())).stream().findFirst()
                .orElseThrow(() -> RecipeRuleException.itemNotFound(request.itemId(), request.variantId()));
        if (request.variantId() != null && !item.variantIds().contains(request.variantId())) {
            throw RecipeRuleException.itemNotFound(request.itemId(), request.variantId());
        }
        return Recipe.forSale(request.itemId(), request.variantId(), request.name(), clock.instant());
    }

    private Recipe preparation(RecipeRequest request) {
        if (request.itemId() != null || request.variantId() != null) {
            throw RecipeRuleException.targetInvalid();
        }
        StockItem output = requireStockItem(request.outputStockItemId());
        if (output.getKind() != StockItemKind.PREPARED) {
            throw RecipeRuleException.outputNotPrepared(output.getId());
        }
        return Recipe.forPreparation(output.getId(), request.name(), clock.instant());
    }

    /**
     * The request's ingredients after the checks: each stock item exists and is written in a unit of its base
     * unit's dimension; the yield is in sold units (COUNT) for a sale recipe or in the output's dimension for a
     * preparation; a preparation never needs its own output.
     */
    private List<IngredientLine> validated(Recipe recipe, RecipeVersionRequest request) {
        if (recipe.isPreparation()) {
            units.toBase(request.yieldQuantity(), request.yieldUnit(),
                    requireStockItem(recipe.getOutputStockItemId()).getBaseUnit());
        } else if (units.unit(request.yieldUnit()).getDimension() != Dimension.COUNT) {
            throw InventoryRuleException.dimensionMismatch(request.yieldUnit(), "un");
        }
        Set<UUID> ids = request.ingredients().stream().map(i -> i.stockItemId()).collect(Collectors.toSet());
        Map<UUID, StockItem> found = stockItems.findAllById(ids).stream()
                .collect(Collectors.toMap(StockItem::getId, Function.identity()));
        ids.stream().filter(id -> !found.containsKey(id)).findFirst().ifPresent(id -> {
            throw new InventoryNotFoundException("StockItem", id);
        });
        List<IngredientLine> lines = request.ingredients().stream().map(i -> {
            units.toBase(i.quantity(), i.unit(), found.get(i.stockItemId()).getBaseUnit());
            return new IngredientLine(i.stockItemId(), i.quantity(), i.unit(), i.kind(),
                    i.modes() == null ? null : i.modes().stream().distinct().toList());
        }).toList();
        if (recipe.isPreparation()) {
            requireNoCycle(recipe.getOutputStockItemId(), ids);
        }
        return lines;
    }

    /**
     * Walks the ingredients through the preparation recipe versions of prepared ingredients that are in force now or
     * later: reaching {@code output} is a cycle (422 RECIPE_CYCLE).
     */
    private void requireNoCycle(UUID output, Set<UUID> ingredients) {
        Deque<UUID> pending = new ArrayDeque<>(ingredients);
        Set<UUID> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            UUID next = pending.pop();
            if (next.equals(output)) {
                throw RecipeRuleException.cycle(output, next);
            }
            if (!seen.add(next)) {
                continue;
            }
            Instant now = clock.instant();
            recipes.findByOutputStockItemId(next).stream()
                    .flatMap(r -> versions.inForceFrom(r.getId(), now).stream())
                    .forEach(used -> used.getIngredients().forEach(i -> pending.push(i.getStockItemId())));
        }
    }

    private RecipeView view(Recipe recipe) {
        return recipe.toView(versions.findByRecipeIdOrderByVersionNo(recipe.getId()).stream()
                .map(RecipeVersion::toView).toList());
    }

    private Recipe require(UUID id) {
        return recipes.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private RecipeVersion requireVersion(UUID recipeId, int versionNo) {
        return versions.findByRecipeIdAndVersionNo(recipeId, versionNo)
                .orElseThrow(() -> new InventoryNotFoundException("RecipeVersion", recipeId + "/" + versionNo));
    }

    private StockItem requireStockItem(UUID id) {
        return stockItems.findById(id).orElseThrow(() -> new InventoryNotFoundException("StockItem", id));
    }
}
