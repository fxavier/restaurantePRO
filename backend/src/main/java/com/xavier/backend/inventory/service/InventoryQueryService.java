package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.StatusView;
import com.xavier.backend.inventory.model.dto.StockRow;
import com.xavier.backend.inventory.repository.InventoryQueries;
import com.xavier.backend.inventory.repository.BalanceProjection;
import com.xavier.backend.shared.events.EventPublicationStats;
import com.xavier.backend.shared.web.PageResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stock, movements and the status of a store, {@code INVENTORY_VIEW} in the store (D113, D116, D118, D122). */
@Service
public class InventoryQueryService {

    /** The listener-id prefix of inventory's consumption listeners (S37 contract), for {@code pendingFacts}. */
    static final String CONSUMPTION_LISTENERS = "com.xavier.backend.inventory.consumption";

    private final InventoryQueries queries;
    private final BalanceProjection projection;
    private final ActivationService activations;
    private final EventPublicationStats publications;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;

    InventoryQueryService(InventoryQueries queries, BalanceProjection projection, ActivationService activations,
                          EventPublicationStats publications, InventoryScope scope, AccessPolicy accessPolicy) {
        this.queries = queries;
        this.projection = projection;
        this.activations = activations;
        this.publications = publications;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public PageResponse<StockRow> stock(UUID warehouseId, boolean belowMinimum, boolean negative,
                                        Pageable pageable) {
        scope.requireWarehouse(Permission.INVENTORY_VIEW, warehouseId);
        return PageResponse.from(queries.stock(warehouseId, belowMinimum, negative, pageable));
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(UUID warehouseId, UUID stockItemId, LocalDate from, LocalDate to,
                                                MovementType type, Pageable pageable) {
        scope.requireWarehouse(Permission.INVENTORY_VIEW, warehouseId);
        Periods.require(from, to);
        return PageResponse.from(queries.movements(warehouseId, stockItemId, from, to,
                type == null ? null : type.name(), pageable));
    }

    @Transactional(readOnly = true)
    public StatusView status(UUID storeId) {
        scope.requireStore(Permission.INVENTORY_VIEW, storeId);
        Optional<Instant> activeFrom = activations.activeFrom(storeId);
        Optional<BalanceProjection.BalanceCheck> check = projection.lastCheck(storeId);
        return new StatusView(storeId, activeFrom.isPresent() ? "ACTIVE" : "INVENTORY_NOT_ACTIVE",
                activeFrom.orElse(null), publications.pendingFor(CONSUMPTION_LISTENERS),
                queries.openExceptions(storeId), queries.negativeBalances(storeId),
                check.map(BalanceProjection.BalanceCheck::driftingBalances).orElse(0),
                check.map(BalanceProjection.BalanceCheck::checkedAt).orElse(null));
    }
}
