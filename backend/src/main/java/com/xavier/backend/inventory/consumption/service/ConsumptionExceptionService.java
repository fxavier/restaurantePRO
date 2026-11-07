package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.consumption.exception.ConsumptionConflictException;
import com.xavier.backend.inventory.consumption.exception.ConsumptionRuleException;
import com.xavier.backend.inventory.consumption.model.ConsumptionException;
import com.xavier.backend.inventory.consumption.model.dto.ExceptionView;
import com.xavier.backend.inventory.consumption.model.dto.ReprocessView;
import com.xavier.backend.inventory.consumption.repository.ConsumptionExceptionRepository;
import com.xavier.backend.inventory.consumption.service.ConsumptionPlanner.Plan;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.service.InventoryScope;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The consumption exceptions of a store (D116): listed with {@code INVENTORY_VIEW}, reprocessed with
 * {@code INVENTORY_REBUILD} in the exception's store once the recipe, route or stock item is fixed. A reprocess uses
 * the recipe version in force at the confirmation, else the first one activated after it (flagged
 * {@code late_recipe}); it posts like the round would have and resolves the exception, or answers 422
 * {@code CONSUMPTION_UNRESOLVED} and changes nothing. Audited.
 */
@Service
public class ConsumptionExceptionService {

    static final String ENTITY = "ConsumptionException";

    private final ConsumptionExceptionRepository exceptions;
    private final OrdersApi orders;
    private final ConsumptionPlanner planner;
    private final RoundConsumption consumption;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    ConsumptionExceptionService(ConsumptionExceptionRepository exceptions, OrdersApi orders,
                                ConsumptionPlanner planner, RoundConsumption consumption, InventoryScope scope,
                                AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.exceptions = exceptions;
        this.orders = orders;
        this.planner = planner;
        this.consumption = consumption;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<ExceptionView> list(UUID storeId, boolean openOnly, Pageable pageable) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        return PageResponse.from((openOnly
                ? exceptions.findByStoreIdAndResolvedAtIsNullOrderByCreatedAtAscIdAsc(storeId, pageable)
                : exceptions.findByStoreIdOrderByCreatedAtAscIdAsc(storeId, pageable))
                .map(ConsumptionException::toView));
    }

    @Transactional
    public ReprocessView reprocess(UUID id) {
        UUID storeId = exceptions.storeOf(id).orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        accessPolicy.require(Permission.INVENTORY_REBUILD, storeId);
        ConsumptionException exception = exceptions.lockById(id)
                .orElseThrow(() -> new InventoryNotFoundException(ENTITY, id));
        if (!exception.isOpen()) {
            throw ConsumptionConflictException.alreadyResolved(id);
        }
        BatchFacts round = orders.batchFacts(exception.getBatchId());
        BatchLineFact line = round.lines().stream().filter(l -> l.lineId().equals(exception.getLineId()))
                .findFirst().orElseThrow(() -> new InventoryNotFoundException("OrderLine", exception.getLineId()));
        SoldUnit unit = SoldUnit.of(line).stream().filter(u -> u.sourceId().equals(exception.getSourceId())
                        && u.sourceType().equals(exception.getSourceType())).findFirst()
                .orElseThrow(() -> new InventoryNotFoundException(exception.getSourceType(),
                        exception.getSourceId()));
        Plan plan = planner.plan(planner.context(round), unit, true);
        if (plan.unresolved() != null) {
            throw ConsumptionRuleException.unresolved(id, plan.unresolved());
        }
        List<MovementView> posted = plan.movements().isEmpty() ? List.of()
                : consumption.post(round, Map.of(line.lineId(), line.quantity()), plan.movements());
        exception.resolve(plan.movements().isEmpty() ? "NOT_TRACKED" : "REPROCESSED", plan.lateRecipe(),
                clock.instant());
        exceptions.flush();
        Map<String, Object> details = new HashMap<>();
        details.put("movements", posted.size());
        details.put("lateRecipe", plan.lateRecipe());
        auditLog.recordAsCurrentActor("CONSUMPTION_EXCEPTION_REPROCESSED", ENTITY, id.toString(), storeId, null,
                null, details);
        return new ReprocessView(exception.toView(), posted);
    }
}
