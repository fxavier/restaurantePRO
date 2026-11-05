package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.hibernate.type.SqlTypes;

/**
 * A bill partition, "conta dividida" (spec §8.1, §8.2; D67): an explicit, immutable allocation of an order's bill.
 * {@code ITEMS} holds shares of order lines (D68, {@link com.xavier.backend.settlement.PartitionAmounts});
 * {@code AMOUNT} a fixed amount of the residual, optionally part {@code partNo} of {@code parts} equal parts. Its only
 * transition is LIVE → CANCELLED (the database refuses any other change). Its due is fixed at creation; what it was
 * paid, and whether it is invoiced, are derived.
 */
@Entity
@Table(schema = "settlement", name = "bill_partition")
public class BillPartition {

    static final int MAX_TEXT = 500;
    static final int MAX_LABEL = 100;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private PartitionKind kind;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false)
    private UUID groupId;

    @Column(updatable = false)
    private Integer partNo;

    @Column(updatable = false)
    private Integer parts;

    @Column(updatable = false, length = MAX_LABEL)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PartitionStatus status;

    @Column(nullable = false, updatable = false)
    private UUID createdByEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant cancelledAt;

    private UUID cancelledByEmployeeId;

    @Column(length = MAX_TEXT)
    private String cancelReason;

    @Version
    private Long version;

    @OneToMany(cascade = CascadeType.PERSIST, fetch = FetchType.EAGER)
    @JoinColumn(name = "partition_id", nullable = false, updatable = false)
    @OrderBy("id")
    private List<BillPartitionLine> lines = new ArrayList<>();

    protected BillPartition() {
    }

    /** An ITEMS partition: one share per order line (D68), computed by the caller from the line's pool. */
    public static BillPartition items(UUID orderId, UUID storeId, List<ItemShare> shares, String label,
                                      UUID employeeId, Instant now) {
        if (shares.isEmpty()) {
            throw new SettlementRuleException(SettlementRule.NOTHING_TO_SPLIT, "An ITEMS partition needs a line.");
        }
        BillPartition partition = start(orderId, storeId, PartitionKind.ITEMS,
                shares.getFirst().share().gross().currency(), label, employeeId, now);
        for (ItemShare share : shares) {
            partition.lines.add(new BillPartitionLine(share.orderLineId(), share.quantity(),
                    share.share(), share.taxCode(), share.ratePercent()));
        }
        return partition;
    }

    /** An AMOUNT partition of {@code amount}, optionally part {@code partNo} of {@code parts} of a group. */
    public static BillPartition amount(UUID orderId, UUID storeId, Money amount, UUID groupId, Integer partNo,
                                       Integer parts, String label, UUID employeeId, Instant now) {
        if (amount.amount().signum() <= 0) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT, "A partition amount must be positive.");
        }
        BillPartition partition = start(orderId, storeId, PartitionKind.AMOUNT, amount.currency(), label,
                employeeId, now);
        partition.amount = amount.amount();
        partition.groupId = groupId;
        partition.partNo = partNo;
        partition.parts = parts;
        return partition;
    }

    private static BillPartition start(UUID orderId, UUID storeId, PartitionKind kind, Currency currency,
                                       String label, UUID employeeId, Instant now) {
        BillPartition partition = new BillPartition();
        partition.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        partition.orderId = Objects.requireNonNull(orderId, "orderId");
        partition.storeId = Objects.requireNonNull(storeId, "storeId");
        partition.kind = kind;
        partition.currency = currency.getCurrencyCode();
        partition.label = label == null || label.isBlank() ? null
                : label.strip().substring(0, Math.min(label.strip().length(), MAX_LABEL));
        partition.status = PartitionStatus.LIVE;
        partition.createdByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        partition.createdAt = now;
        return partition;
    }

    /**
     * LIVE → CANCELLED: its quantities and amount return to the order's pool and residual (D68).
     *
     * @throws SettlementRuleException {@code REASON_REQUIRED}
     * @throws SettlementConflictException 409 {@code ILLEGAL_PARTITION_STATE} once CANCELLED
     */
    public void cancel(String reason, UUID employeeId, Instant now) {
        if (status != PartitionStatus.LIVE) {
            throw SettlementConflictException.illegalPartitionState(id, status, "be cancelled");
        }
        if (reason == null || reason.isBlank()) {
            throw new SettlementRuleException(SettlementRule.REASON_REQUIRED, "A partition cancel needs a reason.");
        }
        status = PartitionStatus.CANCELLED;
        cancelledAt = now;
        cancelledByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        cancelReason = reason.length() <= MAX_TEXT ? reason : reason.substring(0, MAX_TEXT);
    }

    /** What the partition is due: Σ its line shares (ITEMS) or its amount (AMOUNT). */
    public Money due() {
        Currency money = Currency.getInstance(currency);
        if (kind == PartitionKind.AMOUNT) {
            return Money.of(amount, money);
        }
        return lines.stream().map(BillPartitionLine::gross).reduce(Money.of(BigDecimal.ZERO, money), Money::plus);
    }

    public boolean isLive() {
        return status == PartitionStatus.LIVE;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public PartitionKind getKind() {
        return kind;
    }

    public PartitionStatus getStatus() {
        return status;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public Integer getPartNo() {
        return partNo;
    }

    public Integer getParts() {
        return parts;
    }

    public String getLabel() {
        return label;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * The optimistic version ({@code 0} before the first flush). Deliberately not {@code getVersion()}: Spring Data
     * reads the version through the JavaBean getter to tell a new entity, and a non-null 0 would make it merge.
     */
    public long currentVersion() {
        return version == null ? 0 : version;
    }

    public List<BillPartitionLine> getLines() {
        return List.copyOf(lines);
    }

    /** One order line's share for {@link #items}. */
    public record ItemShare(UUID orderLineId, BigDecimal quantity, Share share, String taxCode,
                            BigDecimal ratePercent) {
    }
}
