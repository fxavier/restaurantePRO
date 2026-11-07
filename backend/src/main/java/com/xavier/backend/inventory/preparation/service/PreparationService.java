package com.xavier.backend.inventory.preparation.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.preparation.model.Preparation;
import com.xavier.backend.inventory.preparation.model.dto.PreparationRequest;
import com.xavier.backend.inventory.preparation.model.dto.PreparationResult;
import com.xavier.backend.inventory.preparation.repository.PreparationRepository;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import com.xavier.backend.inventory.recipe.model.RecipeIngredient;
import com.xavier.backend.inventory.recipe.model.RecipeVersion;
import com.xavier.backend.inventory.recipe.repository.RecipeVersionRepository;
import com.xavier.backend.inventory.recipe.service.RecipeBook;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.BusinessDates;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.inventory.service.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Preparations (D121), {@code INVENTORY_MOVE} in the warehouse's store. A draft takes the version of the prepared
 * item's preparation recipe in force when it is created (D115). Confirming it posts, in one transaction: each ingredient's PREPARATION_OUT
 * (output ÷ yield × ingredient, in its base unit, HALF_UP scale 6), valued at the average and manual, so a
 * {@code REFUSE_MANUAL} warehouse refuses it below zero (422 NEGATIVE_STOCK_REFUSED); then one PREPARATION_IN of the
 * output at Σ of those values ÷ quantity; when that unit cost rounds (scale 6), a REVALUATION of the difference keeps
 * the whole cost in the output. Every balance is locked first, in the ledger's order. Audited.
 */
@Service
public class PreparationService {

    public static final String SOURCE_TYPE = "PREPARATION";
    static final String ENTITY = "Preparation";
    private static final String REASON = "preparation";

    private final PreparationRepository preparations;
    private final RecipeBook recipes;
    private final RecipeVersionRepository versions;
    private final StockItemRepository stockItems;
    private final Units units;
    private final StockLedger ledger;
    private final InventoryScope scope;
    private final ActivationService activations;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    PreparationService(PreparationRepository preparations, RecipeBook recipes, RecipeVersionRepository versions,
                       StockItemRepository stockItems, Units units, StockLedger ledger, InventoryScope scope,
                       ActivationService activations, BusinessDates businessDates, AccessPolicy accessPolicy,
                       AuditLog auditLog, Clock clock) {
        this.preparations = preparations;
        this.recipes = recipes;
        this.versions = versions;
        this.stockItems = stockItems;
        this.units = units;
        this.ledger = ledger;
        this.scope = scope;
        this.activations = activations;
        this.businessDates = businessDates;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PreparationResult get(UUID id) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeOf(id));
        return new PreparationResult(require(id).toView(), List.of());
    }

    @Transactional
    public PreparationResult create(PreparationRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_MOVE, request.warehouseId());
        StockItem output = stockItem(request.outputStockItemId());
        RecipeVersion recipe = recipes.preparationVersionAt(output.getId(), clock.instant())
                .orElseThrow(() -> RecipeRuleException.noActiveRecipe(output.getId()));
        BigDecimal quantity = units.toBase(request.outputQuantity(), request.unit(), output.getBaseUnit());
        if (quantity.signum() <= 0) {
            throw InventoryRuleException.invalidQuantity("A preparation makes a positive quantity.");
        }
        Actor actor = accessPolicy.current();
        Preparation preparation = preparations.saveAndFlush(Preparation.draft(storeId, request.warehouseId(),
                recipe.getId(), output.getId(), quantity, request.notes(), actor.kind().name(), idOf(actor),
                clock.instant()));
        auditLog.recordAsCurrentActor("PREPARATION_CREATED", ENTITY, preparation.getId().toString(), storeId, null,
                null, Map.of("outputStockItemId", output.getId().toString(), "quantity", quantity.toPlainString()));
        return new PreparationResult(preparation.toView(), List.of());
    }

    @Transactional
    public PreparationResult confirm(UUID id) {
        UUID storeId = storeOf(id);
        accessPolicy.require(Permission.INVENTORY_MOVE, storeId);
        Preparation preparation = preparations.lockById(id)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        preparation.requireDraft("be confirmed");
        if (activations.activeFrom(storeId).isEmpty()) {
            throw InventoryRuleException.notActive(storeId);
        }
        Instant now = clock.instant();
        List<MovementView> posted = post(preparation, businessDates.of(storeId, now), now);
        Actor actor = accessPolicy.current();
        preparation.confirm(actor.kind().name(), idOf(actor), now);
        preparations.flush();
        auditLog.recordAsCurrentActor("PREPARATION_CONFIRMED", ENTITY, id.toString(), storeId, null, null,
                Map.of("movements", posted.size()));
        return new PreparationResult(preparation.toView(), posted);
    }

    @Transactional
    public PreparationResult cancel(UUID id) {
        UUID storeId = storeOf(id);
        accessPolicy.require(Permission.INVENTORY_MOVE, storeId);
        Preparation preparation = preparations.lockById(id)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        preparation.cancel(clock.instant());
        preparations.flush();
        auditLog.recordAsCurrentActor("PREPARATION_CANCELLED", ENTITY, id.toString(), storeId, null, null,
                Map.of());
        return new PreparationResult(preparation.toView(), List.of());
    }

    private List<MovementView> post(Preparation preparation, LocalDate businessDate, Instant now) {
        RecipeVersion recipe = versions.findById(preparation.getRecipeVersionId()).orElseThrow();
        StockItem output = stockItem(preparation.getOutputStockItemId());
        BigDecimal yield = units.toBase(recipe.getYieldQuantity(), recipe.getYieldUnit(), output.getBaseUnit());
        Map<UUID, BigDecimal> consumed = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : recipe.getIngredients()) {
            BigDecimal share = preparation.getOutputQuantity().multiply(ingredient.getQuantity())
                    .divide(yield, 12, RoundingMode.HALF_UP);
            consumed.merge(ingredient.getStockItemId(), units.toBase(share, ingredient.getUnit(),
                    stockItem(ingredient.getStockItemId()).getBaseUnit()), BigDecimal::add);
        }
        UUID warehouse = preparation.getWarehouseId();
        ledger.lock(Stream.concat(consumed.keySet().stream(), Stream.of(output.getId()))
                .map(item -> new BalanceKey(warehouse, item)).toList());

        List<MovementView> posted = new ArrayList<>(ledger.post(consumed.entrySet().stream()
                .filter(e -> e.getValue().signum() > 0)
                .map(e -> movement(preparation, e.getKey(), MovementType.PREPARATION_OUT, e.getValue().negate(),
                        businessDate, now).manual(REASON)).toList()));
        BigDecimal cost = posted.stream().map(MovementView::value).reduce(BigDecimal.ZERO, BigDecimal::add)
                .negate();
        BigDecimal quantity = preparation.getOutputQuantity();
        List<MovementView> in = ledger.post(List.of(movement(preparation, output.getId(),
                MovementType.PREPARATION_IN, quantity, businessDate, now)
                .atCost(cost.divide(quantity, 6, RoundingMode.HALF_UP))));
        posted.addAll(in);
        BigDecimal residue = cost.subtract(in.getFirst().value());
        if (in.size() == 1 && residue.signum() != 0) {
            posted.addAll(ledger.post(List.of(PostMovement.revaluation(warehouse, output.getId(), residue,
                    SOURCE_TYPE, preparation.getId(), businessDate, now).withReason(REASON))));
        }
        return posted;
    }

    private static PostMovement movement(Preparation preparation, UUID item, MovementType type, BigDecimal quantity,
                                         LocalDate businessDate, Instant now) {
        return PostMovement.of(preparation.getWarehouseId(), item, type, quantity, SOURCE_TYPE, preparation.getId(),
                businessDate, now).withReason(REASON);
    }

    private UUID storeOf(UUID id) {
        return preparations.storeOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private Preparation require(UUID id) {
        return preparations.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private StockItem stockItem(UUID id) {
        return stockItems.findById(id).orElseThrow(() -> new InventoryNotFoundException("StockItem", id));
    }

    private static UUID idOf(Actor actor) {
        return actor.kind() == ActorKind.EMPLOYEE ? actor.employeeId() : actor.userId();
    }
}
