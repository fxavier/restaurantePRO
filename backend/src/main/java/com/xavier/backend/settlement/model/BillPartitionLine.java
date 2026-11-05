package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** One order line's share in an ITEMS partition (D68): a fact of its partition, never changed. */
@Entity
@Immutable
@Table(schema = "settlement", name = "bill_partition_line")
public class BillPartitionLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderLineId;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal gross;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal net;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal tax;

    @Column(updatable = false, length = 30)
    private String taxCode;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal taxRate;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    protected BillPartitionLine() {
    }

    BillPartitionLine(UUID orderLineId, BigDecimal quantity, Share share, String taxCode, BigDecimal taxRate) {
        this.orderLineId = orderLineId;
        this.quantity = quantity;
        this.gross = share.gross().amount();
        this.net = share.net().amount();
        this.tax = share.tax().amount();
        this.taxCode = taxCode;
        this.taxRate = taxRate;
        this.currency = share.gross().currency().getCurrencyCode();
    }

    public UUID getOrderLineId() {
        return orderLineId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Money gross() {
        return Money.of(gross, Currency.getInstance(currency));
    }

    public Money net() {
        return Money.of(net, Currency.getInstance(currency));
    }

    public Money tax() {
        return Money.of(tax, Currency.getInstance(currency));
    }
}
