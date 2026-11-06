package com.xavier.backend.orders.model;

import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A line of an order (spec §8.2, §8.3). A DRAFT is server-side but not a fact: it may be edited or deleted, each
 * guarded by its own {@code @Version}. Confirmation stamps the catalog snapshot (§8.4, D58) and the round; from then
 * on the line is immutable (also in the database: trigger {@code order_line_confirmed_immutable}). Its net quantity
 * comes from the void and transfer facts and is never stored.
 */
@Entity
@Table(schema = "orders", name = "order_line")
public class OrderLine {

    static final int QUANTITY_SCALE = 3;
    static final int QUANTITY_INTEGER_DIGITS = 9;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private LineStatus status;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    private UUID variantId;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(length = 200)
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private UUID createdByEmployeeId;

    @Column(length = 30)
    private String itemCode;

    @Column(length = 120)
    private String description;

    @Column(length = 120)
    private String variantName;

    @Column(precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    private UUID taxCategoryId;

    @Column(length = 30)
    private String taxCode;

    @Column(name = "tax_rate", precision = 7, scale = 4)
    private BigDecimal taxRatePercent;

    private UUID releaseId;

    private Long catalogVersion;

    @Column(length = 30)
    private String destination;

    private Instant confirmedAt;

    private UUID confirmedByEmployeeId;

    private UUID batchId;

    private UUID originTransferId;

    @Version
    private long version;

    protected OrderLine() {
        // JPA
    }

    public static OrderLine draft(UUID orderId, UUID itemId, UUID variantId, BigDecimal quantity, String notes,
                                  UUID employeeId, Instant now) {
        OrderLine line = new OrderLine();
        line.orderId = Objects.requireNonNull(orderId, "orderId");
        line.itemId = Objects.requireNonNull(itemId, "itemId");
        line.status = LineStatus.DRAFT;
        line.createdByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        line.createdAt = Objects.requireNonNull(now, "now");
        line.set(variantId, quantity, notes);
        return line;
    }

    /**
     * The confirmed copy a transfer puts on {@code targetOrderId}: the source's snapshot, item, variant and notes for
     * {@code quantity}, with the transfer as its origin and no round, so production never sees it again (§8.2).
     */
    public static OrderLine transferredCopy(OrderLine source, UUID targetOrderId, BigDecimal quantity,
                                            UUID transferId, UUID employeeId, Instant now) {
        LineSnapshot snapshot = source.snapshot().orElseThrow(() -> OrderConflictException.lineNotConfirmed(source.id));
        OrderLine copy = draft(targetOrderId, source.itemId, source.variantId, quantity, source.notes, employeeId, now);
        copy.stamp(snapshot, employeeId, now);
        copy.originTransferId = Objects.requireNonNull(transferId, "transferId");
        return copy;
    }

    /**
     * A void or a transfer may take {@code quantity} from this line, which has already given up {@code deducted}
     * (Σ voids + Σ transferred out): the net quantity is never stored, so the caller reads it under the order's
     * incremented version (r2-decisions §4 orders).
     *
     * @throws OrderConflictException {@code LINE_NOT_CONFIRMED} (409) for a draft
     * @throws OrderRuleException     {@code QUANTITY_EXCEEDS_NET} (422)
     */
    public void requireAvailable(BigDecimal deducted, BigDecimal quantity) {
        validQuantity(quantity);
        if (status != LineStatus.CONFIRMED) {
            throw OrderConflictException.lineNotConfirmed(id);
        }
        BigDecimal net = this.quantity.subtract(deducted);
        if (quantity.compareTo(net) > 0) {
            throw OrderRuleException.quantityExceedsNet(id, net);
        }
    }

    /** Replaces a draft's variant, quantity and notes. */
    public void edit(UUID variantId, BigDecimal quantity, String notes) {
        requireDraft();
        set(variantId, quantity, notes);
    }

    /** @throws OrderConflictException {@code LINE_NOT_DRAFT} (409) once confirmed */
    public void requireDraft() {
        if (status != LineStatus.DRAFT) {
            throw OrderConflictException.lineNotDraft(id);
        }
    }

    /** DRAFT → CONFIRMED in round {@code batchId}, with what the catalog said at that instant. */
    public void confirm(LineSnapshot snapshot, UUID batchId, UUID employeeId, Instant now) {
        requireDraft();
        stamp(snapshot, employeeId, now);
        this.batchId = Objects.requireNonNull(batchId, "batchId");
    }

    private void stamp(LineSnapshot snapshot, UUID employeeId, Instant now) {
        itemCode = snapshot.itemCode();
        description = snapshot.description();
        variantName = snapshot.variantName();
        unitPrice = snapshot.unitPrice().amount();
        currency = snapshot.unitPrice().currency().getCurrencyCode();
        taxCategoryId = snapshot.taxCategoryId();
        taxCode = snapshot.taxCode();
        taxRatePercent = snapshot.taxRatePercent();
        releaseId = snapshot.releaseId();
        catalogVersion = snapshot.catalogVersion();
        destination = snapshot.destination();
        confirmedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        confirmedAt = Objects.requireNonNull(now, "now");
        status = LineStatus.CONFIRMED;
    }

    /** The confirmed snapshot; empty for a draft. */
    public Optional<LineSnapshot> snapshot() {
        if (status != LineStatus.CONFIRMED) {
            return Optional.empty();
        }
        return Optional.of(new LineSnapshot(itemCode, description, variantName,
                Money.of(unitPrice, Currency.getInstance(currency)), taxCategoryId, taxCode, taxRatePercent,
                releaseId, catalogVersion, destination));
    }

    private void set(UUID variantId, BigDecimal quantity, String notes) {
        this.variantId = variantId;
        this.quantity = validQuantity(quantity);
        this.notes = notes == null || notes.isBlank() ? null : notes.strip();
    }

    /** A quantity is positive, with at most 9 integer digits and 3 decimals ({@code NUMERIC(12,3) > 0}). */
    public static BigDecimal validQuantity(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0 || quantity.stripTrailingZeros().scale() > QUANTITY_SCALE
                || quantity.precision() - quantity.scale() > QUANTITY_INTEGER_DIGITS) {
            throw new IllegalArgumentException("A quantity is positive with at most 3 decimals: " + quantity);
        }
        return quantity.setScale(QUANTITY_SCALE);
    }

    public boolean isDraft() {
        return status == LineStatus.DRAFT;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public LineStatus getStatus() {
        return status;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getOriginTransferId() {
        return originTransferId;
    }

    public UUID getConfirmedByEmployeeId() {
        return confirmedByEmployeeId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public long getVersion() {
        return version;
    }
}
