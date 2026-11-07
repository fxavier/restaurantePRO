package com.xavier.backend.orders.model;

import com.xavier.backend.orders.AdjustmentKind;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * A change of a confirmed line's effective unit price (D105): a fact, insert only (also in the database). The line's
 * effective price is its latest adjustment's ({@code seq}), else its snapshot. It records the price before and after,
 * the net {@code quantity} it applied to, its reason, operator and (when D106 needs one) approver, and for a reward the
 * loyalty entry that paid for it. Its id is drawn before insert, because a split names it (D105).
 */
@Entity
@Immutable
@Table(schema = "orders", name = "order_line_price_adjustment")
public class OrderLinePriceAdjustment implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID lineId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private AdjustmentKind kind;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(updatable = false, precision = 7, scale = 4)
    private BigDecimal percent;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal previousUnitPrice;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false, length = 30)
    private String rewardCode;

    @Column(updatable = false)
    private UUID loyaltyEntryId;

    @Column(nullable = false, updatable = false, length = 200)
    private String reason;

    @Column(nullable = false, updatable = false)
    private UUID operatorEmployeeId;

    @Column(updatable = false)
    private UUID approverEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Transient
    private boolean persisted;

    protected OrderLinePriceAdjustment() {
        // JPA
        persisted = true;
    }

    /** Everything the fact records; {@code reward} is the reward code and loyalty entry of a REWARD, else null. */
    public record Change(AdjustmentKind kind, BigDecimal quantity, BigDecimal percent, Money previousUnitPrice,
                         Money unitPrice, String rewardCode, UUID loyaltyEntryId, String reason,
                         UUID operatorEmployeeId, UUID approverEmployeeId) {
    }

    public static OrderLinePriceAdjustment of(UUID id, OrderLine line, int seq, Change change, Instant now) {
        if (change.reason() == null || change.reason().isBlank()) {
            throw new IllegalArgumentException("An adjustment needs a reason");
        }
        OrderLinePriceAdjustment adjustment = new OrderLinePriceAdjustment();
        adjustment.persisted = false;
        adjustment.id = Objects.requireNonNull(id, "id");
        adjustment.orderId = line.getOrderId();
        adjustment.lineId = Objects.requireNonNull(line.getId(), "line id");
        adjustment.seq = seq;
        adjustment.kind = Objects.requireNonNull(change.kind(), "kind");
        adjustment.quantity = OrderLine.validQuantity(change.quantity());
        adjustment.percent = change.percent();
        adjustment.previousUnitPrice = change.previousUnitPrice().amount();
        adjustment.unitPrice = change.unitPrice().amount();
        adjustment.currency = change.unitPrice().currency().getCurrencyCode();
        adjustment.rewardCode = change.rewardCode();
        adjustment.loyaltyEntryId = change.loyaltyEntryId();
        adjustment.reason = change.reason().strip();
        adjustment.operatorEmployeeId = Objects.requireNonNull(change.operatorEmployeeId(), "operatorEmployeeId");
        adjustment.approverEmployeeId = change.approverEmployeeId();
        adjustment.at = Objects.requireNonNull(now, "now");
        return adjustment;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getLineId() {
        return lineId;
    }

    public int getSeq() {
        return seq;
    }

    public AdjustmentKind getKind() {
        return kind;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public Money previousUnitPrice() {
        return Money.of(previousUnitPrice, Currency.getInstance(currency));
    }

    public Money unitPrice() {
        return Money.of(unitPrice, Currency.getInstance(currency));
    }

    public String getRewardCode() {
        return rewardCode;
    }

    public UUID getLoyaltyEntryId() {
        return loyaltyEntryId;
    }

    public String getReason() {
        return reason;
    }

    public UUID getOperatorEmployeeId() {
        return operatorEmployeeId;
    }

    public UUID getApproverEmployeeId() {
        return approverEmployeeId;
    }

    public Instant getAt() {
        return at;
    }
}
