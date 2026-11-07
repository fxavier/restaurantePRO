package com.xavier.backend.inventory;

import com.xavier.backend.inventory.repository.FactQueries;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.OrganizationApi;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What reporting may read of inventory (D100, D131): read-only (reporting's facade rule, S53) and without
 * authorization (the calling report or listener authorized, or runs as the system). The pull behind
 * {@link com.xavier.backend.inventory.model.event.StockMovementsPosted}.
 */
@Service
@Transactional(readOnly = true)
public class InventoryFacts {

    private final FactQueries facts;
    private final OrganizationApi organization;

    InventoryFacts(FactQueries facts, OrganizationApi organization) {
        this.facts = facts;
        this.organization = organization;
    }

    /** The movements of {@code (sourceType, sourceId)} in posting order; empty when it posted none. */
    public Optional<MovementSet> movementsOf(String sourceType, UUID sourceId) {
        List<MovementFact> movements = facts.movementsOf(sourceType, sourceId);
        return movements.isEmpty() ? Optional.empty()
                : Optional.of(new MovementSet(sourceType, sourceId, movements));
    }

    /**
     * The sources with movements in {@code storeId} on business days {@code from..to} (inclusive), in the order of
     * their first posting; the page's sort is ignored.
     */
    public Page<SourceRef> sourceIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return facts.sourceIds(storeId, from, to, pageable);
    }

    /** The waste a wasted void recorded (D117 as amended), one per sale movement it reclassified; empty if none. */
    public List<WasteFact> wasteOf(UUID voidId) {
        return facts.wasteOf(voidId);
    }

    /**
     * The wasted voids with waste records in {@code storeId} on business days {@code from..to} (inclusive), in the
     * order of their first record, for a reporting rebuild; the page's sort is ignored.
     */
    public Page<UUID> wasteVoidIds(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return facts.wasteVoidIds(storeId, from, to, pageable);
    }

    /**
     * The cost variances of a source {@code StockMovementsPosted} names: a cost correction ({@code COST_CORRECTION})
     * or a supplier return ({@code SUPPLIER_RETURN}, one per linked line with a variance); empty for any other source
     * or when it recorded none.
     */
    public List<CostVarianceFact> costVariancesOf(String sourceType, UUID sourceId) {
        return facts.costVariancesOf(sourceType, sourceId);
    }

    /**
     * The sources with cost variances in {@code storeId} on business days {@code from..to} (inclusive), in recording
     * order, for a reporting rebuild (a correction that revalued nothing posted no movement, so
     * {@link #sourceIds} does not list it); the page's sort is ignored.
     */
    public Page<SourceRef> costVarianceSources(UUID storeId, LocalDate from, LocalDate to, Pageable pageable) {
        return facts.costVarianceSources(storeId, from, to, pageable);
    }

    /**
     * The store's open consumption exceptions (a round line consumed nothing yet: its cost is missing) of its orders'
     * business days {@code from..to} (inclusive), for reporting's finality (S53). An exception recorded before its
     * order's day was kept counts when its line was confirmed from the first instant of {@code from} up to the end of
     * the day after {@code to}: a round confirmed after the cutoff may still belong to the day before.
     */
    public long openExceptionCount(UUID storeId, LocalDate from, LocalDate to) {
        return organization.findStore(storeId).map(store -> facts.openExceptionCount(storeId, from, to,
                BusinessDays.startOf(from, store.timezone(), store.businessDayCutoff()),
                BusinessDays.startOf(to.plusDays(2), store.timezone(), store.businessDayCutoff()))).orElse(0L);
    }

    /** The round a line was consumed from, once consumption settled it; empty before, or for a line never in one. */
    public Optional<UUID> roundOf(UUID lineId) {
        return facts.roundOf(lineId);
    }
}
