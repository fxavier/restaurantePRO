package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.ItemRef;
import com.xavier.backend.inventory.consumption.model.ConsumptionPolicyVersion;
import com.xavier.backend.inventory.consumption.model.ExceptionKind;
import com.xavier.backend.inventory.consumption.repository.ConsumptionPolicyRepository;
import com.xavier.backend.inventory.model.ConsumptionRoute;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.recipe.model.RecipeIngredient;
import com.xavier.backend.inventory.recipe.model.RecipeVersion;
import com.xavier.backend.inventory.recipe.service.RecipeBook;
import com.xavier.backend.inventory.repository.ConsumptionRouteRepository;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.service.Units;
import com.xavier.backend.orders.BatchFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns what a round sold into SALE_CONSUMPTION movements (D116), in the caller's transaction. Per sold unit: the
 * consumption policy in force at the confirmation (by the item's family) says whether it is tracked; the recipe
 * version in force at the confirmation (the variant's, then the item's) says what it consumes, packaging limited to
 * the order's mode; the route of its destination (else the store's default route) says from which warehouse. Per
 * ingredient: sold quantity × ingredient quantity ÷ yield, in the stock item's base unit, HALF_UP at scale 6; the
 * same stock item twice in a recipe is one movement. What cannot be resolved is an exception, never a guess.
 */
@Service
public class ConsumptionPlanner {

    /** The movement reason of a line consumed with a recipe version activated after its confirmation. */
    public static final String LATE_RECIPE = "late_recipe";

    private static final String SOLD_UNIT = "un";
    private static final int WORKING_SCALE = 12;

    private final ConsumptionPolicyRepository policies;
    private final ConsumptionRouteRepository routes;
    private final RecipeBook recipes;
    private final StockItemRepository stockItems;
    private final Units units;
    private final CatalogApi catalog;

    ConsumptionPlanner(ConsumptionPolicyRepository policies, ConsumptionRouteRepository routes, RecipeBook recipes,
                       StockItemRepository stockItems, Units units, CatalogApi catalog) {
        this.policies = policies;
        this.routes = routes;
        this.recipes = recipes;
        this.stockItems = stockItems;
        this.units = units;
        this.catalog = catalog;
    }

    /** What every unit of {@code round} needs looked up once: the policy, the families, the store's routes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Context context(BatchFacts round) {
        Collection<UUID> items = SoldUnit.of(round).stream().map(SoldUnit::itemId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> families = new HashMap<>();
        catalog.items(items).forEach((ItemRef ref) -> {
            if (ref.familyId() != null) {
                families.put(ref.id(), ref.familyId());
            }
        });
        Map<String, UUID> warehouses = new HashMap<>();
        routes.findByStoreIdOrderByDestinationCode(round.storeId()).forEach((ConsumptionRoute route) ->
                warehouses.put(route.getDestinationCode() == null ? "" : route.getDestinationCode(),
                        route.getWarehouseId()));
        return new Context(round, policies.effectiveAt(round.confirmedAt()), families, warehouses);
    }

    /**
     * @param late whether a recipe version activated after the confirmation may be used when none was in force (a
     *             reprocess); its movements are then marked {@link #LATE_RECIPE}
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Plan plan(Context context, SoldUnit unit, boolean late) {
        BatchFacts round = context.round();
        Optional<ConsumptionPolicyVersion> policy = context.policy();
        if (policy.isPresent() && !policy.get().tracks(context.families().get(unit.itemId()))) {
            return Plan.untracked();
        }
        Optional<RecipeVersion> inForce = recipes.saleVersionAt(unit.itemId(), unit.variantId(), round.confirmedAt());
        boolean usedLate = inForce.isEmpty() && late;
        Optional<RecipeVersion> version = usedLate
                ? recipes.firstSaleVersionAfter(unit.itemId(), unit.variantId(), round.confirmedAt()) : inForce;
        if (version.isEmpty()) {
            return Plan.unresolved(ExceptionKind.NO_RECIPE);
        }
        UUID warehouse = Optional.ofNullable(unit.destination()).map(context.warehouses()::get)
                .orElse(context.warehouses().get(""));
        if (warehouse == null) {
            return Plan.unresolved(ExceptionKind.NO_ROUTE);
        }
        String mode = round.mode() == null ? null : round.mode().name();
        List<RecipeIngredient> ingredients = version.get().getIngredients().stream()
                .filter(ingredient -> ingredient.appliesTo(mode)).toList();
        Map<UUID, StockItem> items = stockItems.findAllById(ingredients.stream()
                        .map(RecipeIngredient::getStockItemId).toList()).stream()
                .collect(Collectors.toMap(StockItem::getId, Function.identity()));
        if (items.values().stream().anyMatch(item -> !item.isActive())) {
            return Plan.unresolved(ExceptionKind.INACTIVE_STOCK_ITEM);
        }
        BigDecimal yield = units.toBase(version.get().getYieldQuantity(), version.get().getYieldUnit(), SOLD_UNIT);
        Map<UUID, BigDecimal> consumed = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : ingredients) {
            BigDecimal perSale = unit.quantity().multiply(ingredient.getQuantity())
                    .divide(yield, WORKING_SCALE, RoundingMode.HALF_UP);
            consumed.merge(ingredient.getStockItemId(), units.toBase(perSale, ingredient.getUnit(),
                    items.get(ingredient.getStockItemId()).getBaseUnit()), BigDecimal::add);
        }
        UUID policyVersion = policy.map(ConsumptionPolicyVersion::getId).orElse(null);
        List<PostMovement> movements = consumed.entrySet().stream().filter(e -> e.getValue().signum() > 0)
                .map(e -> {
                    PostMovement movement = PostMovement.of(warehouse, e.getKey(), MovementType.SALE_CONSUMPTION,
                                    e.getValue().negate(), unit.sourceType(), unit.sourceId(), round.businessDay(),
                                    round.confirmedAt())
                            .withSourceLine(unit.lineId())
                            .fromSale(unit.itemId(), version.get().getId(), policyVersion);
                    return usedLate ? movement.withReason(LATE_RECIPE) : movement;
                }).toList();
        return new Plan(movements, null, usedLate);
    }

    /** The round and what its units share. {@code warehouses} maps a destination ("" = default) to its warehouse. */
    public record Context(BatchFacts round, Optional<ConsumptionPolicyVersion> policy, Map<UUID, UUID> families,
                          Map<String, UUID> warehouses) {
    }

    /**
     * The movements of one sold unit (empty when untracked or when nothing of the recipe applies to the mode), or
     * why it cannot be consumed ({@code unresolved}).
     */
    public record Plan(List<PostMovement> movements, ExceptionKind unresolved, boolean lateRecipe) {

        static Plan untracked() {
            return new Plan(List.of(), null, false);
        }

        static Plan unresolved(ExceptionKind kind) {
            return new Plan(List.of(), kind, false);
        }
    }
}
