package com.xavier.backend.inventory.count.model;

import com.xavier.backend.inventory.count.exception.CountConflictException;
import com.xavier.backend.inventory.count.exception.CountRuleException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A stock count of one warehouse (D119), with the §8.3-style machine of {@link CountStatus}. Lines are recorded only
 * while IN_PROGRESS; submitting freezes them; approval settles each line at its own cut-off. A decided count is a
 * fact and is never changed (a rejected count is kept).
 */
@Entity
@Table(schema = "inventory", name = "stock_count")
public class StockCount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private CountKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 4)
    private CountScope scope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private CountStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(updatable = false)
    private UUID createdByAccountId;

    private Instant startedAt;

    private Instant submittedAt;

    private Instant decidedAt;

    private UUID decidedByAccountId;

    private String rejectReason;

    private Instant cancelledAt;

    @OneToMany(cascade = CascadeType.ALL)
    @JoinColumn(name = "count_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<StockCountLine> lines = new ArrayList<>();

    @Version
    private Long version;

    protected StockCount() {
        // JPA
    }

    /**
     * @param listed the items of a LIST count with their base unit, in order; empty for an ALL count
     * @throws CountRuleException COUNT_SCOPE_INVALID when a LIST names no item or an ALL count names some
     */
    public static StockCount create(UUID storeId, UUID warehouseId, CountKind kind, CountScope scope,
                                    Map<UUID, String> listed, UUID accountId, Instant now) {
        if (scope == CountScope.LIST && listed.isEmpty()) {
            throw CountRuleException.scopeInvalid("A LIST count names the stock items to count.");
        }
        if (scope == CountScope.ALL && !listed.isEmpty()) {
            throw CountRuleException.scopeInvalid("An ALL count names no stock items: any item may be counted.");
        }
        StockCount count = new StockCount();
        count.storeId = storeId;
        count.warehouseId = warehouseId;
        count.kind = kind;
        count.scope = scope;
        count.status = CountStatus.DRAFT;
        count.createdAt = now;
        count.createdByAccountId = accountId;
        listed.forEach((item, unit) -> count.lines.add(StockCountLine.of(item, unit)));
        return count;
    }

    public void start(Instant now) {
        require(CountStatus.DRAFT, "start");
        status = CountStatus.IN_PROGRESS;
        startedAt = now;
    }

    /**
     * Records (or re-records) what was counted of {@code stockItemId}, in its base unit {@code baseUnit}.
     *
     * @param unitCost an opening's cost per base unit: required when something was counted, refused on a regular
     *                 count
     * @throws InventoryNotFoundException when a LIST count does not name the item
     */
    public StockCountLine record(UUID stockItemId, String baseUnit, BigDecimal quantity, Instant countedAt,
                                 BigDecimal unitCost, String actorKind, UUID actorId, Instant now) {
        require(CountStatus.IN_PROGRESS, "record a line");
        if (countedAt.isAfter(now)) {
            throw CountRuleException.countedAtInFuture(countedAt);
        }
        if (kind == CountKind.REGULAR && unitCost != null) {
            throw CountRuleException.unitCostOpeningOnly();
        }
        if (kind == CountKind.OPENING && unitCost == null && quantity.signum() > 0) {
            throw InventoryRuleException.unitCostRequired(stockItemId);
        }
        StockCountLine line = line(stockItemId).orElseGet(() -> {
            if (scope == CountScope.LIST) {
                throw new InventoryNotFoundException("StockCountLine", id + "/" + stockItemId);
            }
            StockCountLine added = StockCountLine.of(stockItemId, baseUnit);
            lines.add(added);
            return added;
        });
        line.record(quantity, countedAt, unitCost, actorKind, actorId);
        return line;
    }

    /** @throws CountRuleException COUNT_INCOMPLETE with no line or a line not counted */
    public void submit(Instant now) {
        require(CountStatus.IN_PROGRESS, "be submitted");
        if (lines.isEmpty() || lines.stream().anyMatch(line -> !line.isCounted())) {
            throw CountRuleException.incomplete(id);
        }
        status = CountStatus.SUBMITTED;
        submittedAt = now;
    }

    /**
     * Settles every line against the quantity expected at its cut-off and approves the count.
     *
     * @param expectedByItem Σ movements of each counted item with {@code occurred_at ≤ counted_at}
     * @return the difference per item (counted − expected)
     */
    public Map<UUID, BigDecimal> approve(Map<UUID, BigDecimal> expectedByItem, UUID accountId, Instant now) {
        requireApprovable();
        Map<UUID, BigDecimal> differences = new LinkedHashMap<>();
        for (StockCountLine line : lines) {
            BigDecimal expected = expectedByItem.get(line.getStockItemId());
            if (expected == null) {
                throw new IllegalArgumentException("no expected quantity for " + line.getStockItemId());
            }
            differences.put(line.getStockItemId(), line.settle(expected));
        }
        status = CountStatus.APPROVED;
        decide(accountId, now);
        return differences;
    }

    /** @throws CountConflictException COUNT_STATE unless SUBMITTED */
    public void requireApprovable() {
        require(CountStatus.SUBMITTED, "be approved");
    }

    public void reject(String reason, UUID accountId, Instant now) {
        require(CountStatus.SUBMITTED, "be rejected");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a rejection has a reason");
        }
        status = CountStatus.REJECTED;
        rejectReason = reason.strip();
        decide(accountId, now);
    }

    public void cancel(Instant now) {
        if (status != CountStatus.DRAFT && status != CountStatus.IN_PROGRESS) {
            throw CountConflictException.state(id, status, "be cancelled");
        }
        status = CountStatus.CANCELLED;
        cancelledAt = now;
    }

    /** The latest cut-off of the count's lines. */
    public Instant latestCutOff() {
        return lines.stream().map(StockCountLine::getCountedAt).max(Instant::compareTo).orElseThrow();
    }

    private void decide(UUID accountId, Instant now) {
        decidedAt = now;
        decidedByAccountId = accountId;
    }

    private void require(CountStatus expected, String action) {
        if (status != expected) {
            throw CountConflictException.state(id, status, action);
        }
    }

    private Optional<StockCountLine> line(UUID stockItemId) {
        return lines.stream().filter(line -> line.getStockItemId().equals(stockItemId)).findFirst();
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public CountKind getKind() {
        return kind;
    }

    public CountScope getScope() {
        return scope;
    }

    public CountStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedByAccountId() {
        return decidedByAccountId;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public List<StockCountLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
