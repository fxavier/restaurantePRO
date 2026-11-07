package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.inventory.exception.InventoryConflictException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.MovingAverage.Valued;
import com.xavier.backend.inventory.model.NegativePolicy;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.Warehouse;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import com.xavier.backend.inventory.repository.LedgerStore;
import com.xavier.backend.inventory.repository.LedgerStore.NewMovement;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.repository.WarehouseRepository;
import com.xavier.backend.organization.OrganizationApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one posting path of the stock ledger (D113; S47–S49 post through it and never write movements themselves).
 * In the caller's transaction it:
 * <ol>
 *     <li>creates the missing balance rows and locks every balance it touches {@code FOR UPDATE}, in
 *     {@link BalanceKey#LOCK_ORDER} (D132), so postings on the same items serialize and never deadlock;</li>
 *     <li>values each movement at the moving average (its own cost for an IN that has one), with a REVALUATION when
 *     an IN brings a negative quantity back;</li>
 *     <li>refuses a manual OUT below zero in a {@code REFUSE_MANUAL} warehouse (422 {@code NEGATIVE_STOCK_REFUSED},
 *     D118); every other movement posts, even below zero;</li>
 *     <li>inserts with {@code ON CONFLICT DO NOTHING} on the source key: a repeated movement is returned as
 *     recorded and moves nothing, one with other values is a 409 {@code MOVEMENT_SOURCE_MISMATCH};</li>
 *     <li>publishes {@link StockMovementsPosted} once per source and transaction, when something was posted.</li>
 * </ol>
 * It never authorizes: the calling use case did.
 *
 * <p><b>A caller that posts more than once in one transaction</b> (a same-store transfer's OUT and IN; S47–S49 when
 * they post in steps) <b>must first {@link #lock} every balance it will touch</b>: each {@code post} sorts only its own
 * keys, so two posts would lock in posting order, and two such transactions in opposite orders deadlock. Locking
 * again a row the transaction holds is a no-op, so the later posts keep the order.
 */
@Service
public class StockLedger {

    private static final String POSTED_SOURCES = StockLedger.class.getName() + ".postedSources";

    private final LedgerStore store;
    private final WarehouseRepository warehouses;
    private final StockItemRepository items;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    StockLedger(LedgerStore store, WarehouseRepository warehouses, StockItemRepository items,
                OrganizationApi organization, AccessPolicy accessPolicy, ApplicationEventPublisher events,
                Clock clock) {
        this.store = store;
        this.warehouses = warehouses;
        this.items = items;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Creates the missing balance rows of {@code keys} and locks them all {@code FOR UPDATE} in
     * {@link BalanceKey#LOCK_ORDER}, until the transaction ends. For a transaction that posts more than once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(Collection<BalanceKey> keys) {
        List<BalanceKey> sorted = keys.stream().distinct().sorted(BalanceKey.LOCK_ORDER).toList();
        if (sorted.isEmpty()) {
            return;
        }
        store.ensureBalances(sorted, clock.instant());
        store.lock(sorted);
    }

    /** @return the movements of the request (as recorded when replayed), each followed by its REVALUATION if any */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> post(List<PostMovement> movements) {
        if (movements.isEmpty()) {
            return List.of();
        }
        Map<UUID, Warehouse> warehouseById = warehousesOf(movements);
        requireItems(movements);
        Instant now = clock.instant();
        List<BalanceKey> keys = movements.stream().map(StockLedger::keyOf).distinct()
                .sorted(BalanceKey.LOCK_ORDER).toList();
        store.ensureBalances(keys, now);
        Map<BalanceKey, StockPosition> positions = new HashMap<>(store.lock(keys));
        Map<BalanceKey, Long> lastSeq = new HashMap<>();
        Attribution by = attribution(now);

        List<MovementView> result = new ArrayList<>();
        Map<String, MovementView> postedSources = new LinkedHashMap<>();
        for (PostMovement movement : movements) {
            BalanceKey key = keyOf(movement);
            Warehouse warehouse = warehouseById.get(movement.warehouseId());
            StockPosition before = positions.get(key);
            Valued valued = movement.type() == MovementType.REVALUATION
                    ? MovingAverage.revalue(before, movement.value())
                    : MovingAverage.post(before, movement.quantity(), movement.unitCost(), movement.value());
            if (refusedBelowZero(movement, warehouse, valued.after())) {
                if (store.findBySourceKey(movement.sourceType(), movement.sourceId(), movement.stockItemId(),
                        movement.type().name(), movement.warehouseId()).isPresent()) {
                    result.add(recorded(movement)); // a replay is answered as recorded, whatever the stock now
                    continue;
                }
                throw InventoryRuleException.negativeStockRefused(warehouse.getId(), movement.stockItemId(),
                        before.quantity());
            }

            Optional<MovementView> inserted = store.insert(row(movement, warehouse, movement.type(),
                    movement.quantity(), valued.unitCost(), valued.value(), referenceCost(movement), by));
            if (inserted.isEmpty()) {
                result.add(recorded(movement));
                continue;
            }
            result.add(inserted.get());
            if (valued.revaluation() != null) {
                MovementView revaluation = store.insert(row(movement, warehouse, MovementType.REVALUATION,
                                BigDecimal.ZERO, valued.unitCost(), valued.revaluation(), null, by))
                        .orElseThrow(() -> InventoryConflictException.sourceMismatch(movement.sourceType(),
                                movement.sourceId()));
                result.add(revaluation);
            }
            positions.put(key, valued.after());
            lastSeq.put(key, result.getLast().postingSeq());
            postedSources.putIfAbsent(movement.sourceType() + ":" + movement.sourceId(), inserted.get());
        }
        lastSeq.forEach((key, seq) -> store.updateBalance(key, positions.get(key), seq, now));
        publishOncePerSource(postedSources);
        return result;
    }

    private Map<UUID, Warehouse> warehousesOf(List<PostMovement> movements) {
        Set<UUID> ids = movements.stream().map(PostMovement::warehouseId).collect(Collectors.toSet());
        Map<UUID, Warehouse> found = warehouses.findAllById(ids).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        ids.stream().filter(id -> !found.containsKey(id)).findFirst().ifPresent(id -> {
            throw new InventoryNotFoundException(WarehouseService.ENTITY, id);
        });
        return found;
    }

    private void requireItems(List<PostMovement> movements) {
        Set<UUID> ids = movements.stream().map(PostMovement::stockItemId).collect(Collectors.toSet());
        if (items.countByIdIn(ids) != ids.size()) {
            Set<UUID> missing = new HashSet<>(ids);
            items.findAllById(ids).forEach(item -> missing.remove(item.getId()));
            throw new InventoryNotFoundException(StockItemService.ENTITY, missing.iterator().next());
        }
    }

    private static boolean refusedBelowZero(PostMovement movement, Warehouse warehouse, StockPosition after) {
        return movement.manual() && movement.quantity().signum() < 0
                && warehouse.getNegativePolicy() == NegativePolicy.REFUSE_MANUAL && after.quantity().signum() < 0;
    }

    /** The movement already recorded for this source key, which must be the same movement. */
    private MovementView recorded(PostMovement movement) {
        MovementView existing = store.findBySourceKey(movement.sourceType(), movement.sourceId(),
                movement.stockItemId(), movement.type().name(), movement.warehouseId()).orElseThrow();
        BigDecimal recordedCost = movement.quantity().signum() < 0 ? existing.referenceUnitCost() : existing.unitCost();
        boolean same = existing.quantity().compareTo(movement.quantity()) == 0
                && (movement.unitCost() == null
                || recordedCost != null && recordedCost.compareTo(movement.unitCost()) == 0)
                && (movement.value() == null || existing.value().compareTo(movement.value()) == 0)
                && Objects.equals(existing.sourceLineId(), movement.sourceLineId())
                && Objects.equals(existing.reversesMovementId(), movement.reversesMovementId());
        if (!same) {
            throw InventoryConflictException.sourceMismatch(movement.sourceType(), movement.sourceId());
        }
        return existing;
    }

    private void publishOncePerSource(Map<String, MovementView> postedSources) {
        Set<String> published = publishedInThisTransaction();
        postedSources.forEach((source, first) -> {
            if (published.add(source)) {
                events.publishEvent(new StockMovementsPosted(first.sourceType(), first.sourceId(), first.storeId(),
                        first.businessDate()));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Set<String> publishedInThisTransaction() {
        Set<String> published = (Set<String>) TransactionSynchronizationManager.getResource(POSTED_SOURCES);
        if (published == null) {
            Set<String> fresh = new HashSet<>();
            TransactionSynchronizationManager.bindResource(POSTED_SOURCES, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResource(POSTED_SOURCES);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(POSTED_SOURCES, fresh);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(POSTED_SOURCES);
                }
            });
            return fresh;
        }
        return published;
    }

    private Attribution attribution(Instant now) {
        String currency = organization.companyProfile().currency().getCurrencyCode();
        Optional<Actor> actor = accessPolicy.currentIfPresent();
        if (actor.isEmpty()) {
            return new Attribution("SYSTEM", null, currency, now);
        }
        Actor current = actor.get();
        UUID id = current.kind() == ActorKind.EMPLOYEE ? current.employeeId() : current.userId();
        return new Attribution(current.kind().name(), id, currency, now);
    }

    /** The cost an OUT names, kept for reference only (it is valued at the average). */
    private static BigDecimal referenceCost(PostMovement movement) {
        return movement.quantity().signum() < 0 ? movement.unitCost() : null;
    }

    private static NewMovement row(PostMovement m, Warehouse warehouse, MovementType type, BigDecimal quantity,
                                   BigDecimal unitCost, BigDecimal value, BigDecimal referenceUnitCost,
                                   Attribution by) {
        return new NewMovement(UuidVersion7Strategy.INSTANCE.generateUuid(null), m.warehouseId(),
                warehouse.getStoreId(), m.stockItemId(), type.name(), quantity, unitCost, referenceUnitCost, value,
                by.currency(),
                m.sourceType(), m.sourceId(), m.sourceLineId(), m.reversesMovementId(), m.recipeVersionId(),
                m.policyVersionId(), m.saleItemId(), m.businessDate(), m.occurredAt(), by.at(), by.kind(), by.id(),
                m.reason());
    }

    private static BalanceKey keyOf(PostMovement movement) {
        return new BalanceKey(movement.warehouseId(), movement.stockItemId());
    }

    private record Attribution(String kind, UUID id, String currency, Instant at) {
    }
}
