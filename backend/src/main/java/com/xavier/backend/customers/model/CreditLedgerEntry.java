package com.xavier.backend.customers.model;

import com.xavier.backend.customers.LedgerEntryView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A fact of the customer's credit ledger ("CreditLedgerEntry", D73). The database refuses UPDATE and DELETE, and
 * {@code UNIQUE(source_type, source_id, kind)} keeps one entry per source: a repeated post adds nothing. A
 * correction is another entry (a refund DEBIT reverses a payment CREDIT).
 */
@Entity
@Immutable
@Table(schema = "customers", name = "credit_ledger_entry")
public class CreditLedgerEntry {

    public static final String SOURCE_UK = "credit_ledger_entry_source_uk";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 6)
    private EntryKind kind;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private EntrySource sourceType;

    @Column(nullable = false, updatable = false)
    private UUID sourceId;

    @Column(updatable = false)
    private UUID documentId;

    @Column(updatable = false)
    private UUID storeId;

    @Column(updatable = false)
    private LocalDate businessDate;

    @Column(updatable = false, length = 200)
    private String description;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected CreditLedgerEntry() {
        // JPA
    }

    private CreditLedgerEntry(UUID customerId, EntryKind kind, Money amount, EntrySource sourceType, UUID sourceId,
                              UUID documentId, UUID storeId, LocalDate businessDate, String description,
                              Instant now) {
        if (amount.amount().signum() <= 0) {
            throw new IllegalArgumentException("A ledger amount must be positive");
        }
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.kind = kind;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.sourceType = sourceType;
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.documentId = documentId;
        this.storeId = storeId;
        this.businessDate = businessDate;
        this.description = description;
        this.occurredAt = now;
    }

    /** The debt of a credit sale, once its document is issued: it consumes the charge's reservation. */
    public static CreditLedgerEntry saleDebit(CreditReservation reservation, UUID documentId, UUID storeId,
                                              LocalDate businessDate, Instant now) {
        return new CreditLedgerEntry(reservation.getCustomerId(), EntryKind.DEBIT, reservation.amount(),
                EntrySource.CREDIT_CHARGE, reservation.getChargeId(), Objects.requireNonNull(documentId, "documentId"),
                Objects.requireNonNull(storeId, "storeId"), Objects.requireNonNull(businessDate, "businessDate"),
                "Credit sale", now);
    }

    public static CreditLedgerEntry paymentCredit(UUID customerId, UUID paymentId, Money amount, UUID storeId,
                                                  LocalDate businessDate, Instant now) {
        return new CreditLedgerEntry(customerId, EntryKind.CREDIT, amount, EntrySource.PAYMENT, paymentId, null,
                Objects.requireNonNull(storeId, "storeId"), Objects.requireNonNull(businessDate, "businessDate"),
                "Debt payment", now);
    }

    /** The refund of a debt payment: the debt comes back, in the payment's store. */
    public static CreditLedgerEntry refundDebit(CreditLedgerEntry payment, UUID refundId, Money amount, Instant now) {
        return new CreditLedgerEntry(payment.customerId, EntryKind.DEBIT, amount, EntrySource.REFUND, refundId, null,
                payment.storeId, null, "Debt payment refund", now);
    }

    public LedgerEntryView toView() {
        return new LedgerEntryView(id, customerId, kind.name(), sourceType.name(), sourceId, documentId, storeId,
                businessDate, amount(), description, occurredAt);
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }
}
