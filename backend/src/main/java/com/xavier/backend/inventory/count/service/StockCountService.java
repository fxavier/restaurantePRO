package com.xavier.backend.inventory.count.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.ActorKind;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.count.exception.CountConflictException;
import com.xavier.backend.inventory.count.model.CountKind;
import com.xavier.backend.inventory.count.model.StockCount;
import com.xavier.backend.inventory.count.model.StockCountLine;
import com.xavier.backend.inventory.count.model.dto.CountLineRequest;
import com.xavier.backend.inventory.count.model.dto.CountRequest;
import com.xavier.backend.inventory.count.model.dto.CountView;
import com.xavier.backend.inventory.count.model.dto.RejectCountRequest;
import com.xavier.backend.inventory.count.repository.CountLedgerQueries;
import com.xavier.backend.inventory.count.repository.CountRef;
import com.xavier.backend.inventory.count.repository.StockCountRepository;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.inventory.model.StockItem;
import com.xavier.backend.inventory.repository.StockItemRepository;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.inventory.service.Units;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counting (D119): create, start, record lines, submit, reject and cancel, with {@code INVENTORY_COUNT} in the
 * warehouse's store ({@code INVENTORY_COUNT_APPROVE} to reject). A regular count needs a live store (422
 * INVENTORY_NOT_ACTIVE); starting an opening count is how a store goes live. Commands authorize on the count's store first,
 * then lock its row, so lines and status changes serialize. Approval is {@link CountApprovalService}.
 */
@Service
public class StockCountService {

    static final String ENTITY = "StockCount";

    private final StockCountRepository counts;
    private final CountLedgerQueries ledgerQueries;
    private final StockItemRepository items;
    private final Units units;
    private final InventoryScope scope;
    private final ActivationService activations;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    StockCountService(StockCountRepository counts, CountLedgerQueries ledgerQueries, StockItemRepository items, Units units, InventoryScope scope,
                      ActivationService activations, AccessPolicy accessPolicy, AuditLog auditLog,
                      Clock clock) {
        this.counts = counts;
        this.ledgerQueries = ledgerQueries;
        this.items = items;
        this.units = units;
        this.scope = scope;
        this.activations = activations;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** @throws CountConflictException LIVE_COUNT_EXISTS (409) when the warehouse has a count not decided yet */
    @Transactional
    public CountView create(CountRequest request) {
        UUID storeId = scope.requireWarehouse(Permission.INVENTORY_COUNT, request.warehouseId());
        if (request.kind() == CountKind.REGULAR && activations.activeFrom(storeId).isEmpty()) {
            throw InventoryRuleException.notActive(storeId);
        }
        Map<UUID, String> listed = new LinkedHashMap<>();
        for (UUID itemId : request.listed()) {
            if (listed.put(itemId, item(itemId).getBaseUnit()) != null) {
                throw InventoryRuleException.duplicateStockItem(itemId);
            }
        }
        StockCount count = UniqueConstraints.saveAndFlush(counts, StockCount.create(storeId, request.warehouseId(),
                        request.kind(), request.scope(), listed, accessPolicy.current().userId(), clock.instant()),
                () -> CountConflictException.liveCountExists(request.warehouseId()), "stock_count_live_ux");
        audit("STOCK_COUNT_CREATED", count, null, Map.of());
        return view(count);
    }

    /**
     * Starts counting. A REGULAR ALL count gets a line for every item on hand. Starting an OPENING count makes the
     * store live from that instant (D122a): it activates a store that is not live, or brings a go-live scheduled
     * for later forward to now. That cannot be undone, so an opening also needs {@code INVENTORY_MANAGE}, like an
     * explicit activation. Sales are consumed from then on, and the opening absorbs those before each cut-off.
     */
    @Transactional
    public CountView start(UUID id) {
        CountRef ref = counts.refOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        accessPolicy.require(Permission.INVENTORY_COUNT, ref.storeId());
        boolean opening = ref.kind() == CountKind.OPENING;
        if (opening) {
            accessPolicy.require(Permission.INVENTORY_MANAGE, ref.storeId());
        }
        StockCount count = counts.findForUpdate(id)
                .orElseThrow(() -> new InventoryNotFoundException(StockCountService.ENTITY, id));
        Instant now = clock.instant();
        count.start(now, count.listsOnHandAtStart() ? ledgerQueries.onHand(count.getWarehouseId()) : Map.of());
        CountView view = saved("STOCK_COUNT_STARTED", count);
        if (opening) {
            goLive(count, now);
        }
        return view;
    }

    private void goLive(StockCount count, Instant now) {
        UUID storeId = count.getStoreId();
        Map<String, Object> details = new HashMap<>();
        details.put("activeFrom", now.toString());
        details.put("openingCountId", count.getId().toString());
        if (ledgerQueries.activateIfAbsent(storeId, now, accessPolicy.current().userId(), now)) {
            auditLog.recordAsCurrentActor("INVENTORY_ACTIVATED", "InventoryActivation", storeId.toString(), storeId,
                    null, null, details);
            return;
        }
        ledgerQueries.bringActivationForward(storeId, now).ifPresent(scheduled -> {
            details.put("previousActiveFrom", scheduled.toString());
            auditLog.recordAsCurrentActor("INVENTORY_ACTIVATION_MOVED", "InventoryActivation", storeId.toString(),
                    storeId, null, null, details);
        });
    }

    /** Records what was counted of one item (again, while IN_PROGRESS). Quantities are converted to the base unit. */
    @Transactional
    public CountView recordLine(UUID id, UUID stockItemId, CountLineRequest request) {
        StockCount count = lockAuthorized(id, Permission.INVENTORY_COUNT);
        StockItem item = item(stockItemId);
        BigDecimal quantity = units.toBase(request.countedQuantity(), request.unit(), item.getBaseUnit());
        Instant now = clock.instant();
        Instant countedAt = request.countedAt() == null ? now : request.countedAt();
        Actor actor = accessPolicy.current();
        StockCountLine line = count.record(item.getId(), item.getBaseUnit(), quantity, countedAt, request.unitCost(),
                actor.kind().name(), recordedId(actor), now);
        StockCount saved = counts.saveAndFlush(count);
        Map<String, Object> details = new HashMap<>();
        details.put("stockItemId", item.getId().toString());
        details.put("countedQuantity", quantity.toPlainString());
        details.put("countedAt", countedAt.toString());
        if (line.getUnitCost() != null) {
            details.put("unitCost", line.getUnitCost().toPlainString());
        }
        audit("STOCK_COUNT_LINE_RECORDED", saved, null, details);
        return view(saved);
    }

    @Transactional
    public CountView submit(UUID id) {
        StockCount count = lockAuthorized(id, Permission.INVENTORY_COUNT);
        count.submit(clock.instant());
        return saved("STOCK_COUNT_SUBMITTED", count);
    }

    /** A rejected count is kept with its lines and posts nothing; the warehouse may then be counted again. */
    @Transactional
    public CountView reject(UUID id, RejectCountRequest request) {
        StockCount count = lockAuthorized(id, Permission.INVENTORY_COUNT_APPROVE);
        count.reject(request.reason(), accessPolicy.current().userId(), clock.instant());
        StockCount saved = counts.saveAndFlush(count);
        audit("STOCK_COUNT_REJECTED", saved, saved.getRejectReason(), Map.of());
        return view(saved);
    }

    @Transactional
    public CountView cancel(UUID id) {
        StockCount count = lockAuthorized(id, Permission.INVENTORY_COUNT);
        count.cancel(clock.instant());
        return saved("STOCK_COUNT_CANCELLED", count);
    }

    @Transactional(readOnly = true)
    public CountView get(UUID id) {
        accessPolicy.require(Permission.INVENTORY_VIEW, storeOf(id));
        return view(counts.findById(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id)));
    }

    /**
     * The id a line records for its counter: the account for a BackOffice session, the employee for a POS one
     * ({@link Actor#userId()} equals the employee id then), so the approver is compared on the same key.
     */
    static UUID recordedId(Actor actor) {
        return actor.kind() == ActorKind.EMPLOYEE ? actor.employeeId() : actor.userId();
    }

    private CountView saved(String action, StockCount count) {
        StockCount saved = counts.saveAndFlush(count);
        audit(action, saved, null, Map.of());
        return view(saved);
    }

    /** D42: the count's store alone (404), then {@code permission} there (403), then the row FOR UPDATE. */
    private StockCount lockAuthorized(UUID id, Permission permission) {
        accessPolicy.require(permission, storeOf(id));
        return counts.findForUpdate(id)
                .orElseThrow(() -> new InventoryNotFoundException(StockCountService.ENTITY, id));
    }

    private UUID storeOf(UUID id) {
        return counts.storeOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
    }

    private StockItem item(UUID id) {
        return items.findById(id).orElseThrow(() -> new InventoryNotFoundException("StockItem", id));
    }

    private void audit(String action, StockCount count, String reason, Map<String, Object> extra) {
        Map<String, Object> details = new HashMap<>(extra);
        details.put("warehouseId", count.getWarehouseId().toString());
        details.put("kind", count.getKind().name());
        details.put("scope", count.getScope().name());
        details.put("status", count.getStatus().name());
        auditLog.recordAsCurrentActor(action, ENTITY, count.getId().toString(), count.getStoreId(), null, reason,
                details);
    }

    static CountView view(StockCount count) {
        return new CountView(count.getId(), count.getStoreId(), count.getWarehouseId(), count.getKind().name(),
                count.getScope().name(), count.getStatus().name(), count.getCreatedAt(), count.getStartedAt(),
                count.getSubmittedAt(), count.getDecidedAt(), count.getDecidedByAccountId(), count.getRejectReason(),
                count.getCancelledAt(), count.getLines().stream().map(line -> new CountView.Line(line.getId(),
                        line.getStockItemId(), line.getCountedQuantity(), line.getUnit(), line.getCountedAt(),
                        line.getUnitCost(), line.getExpectedQuantity(), line.getDifference())).toList(),
                count.getVersion());
    }
}
