package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.dto.CreditChargeView;
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
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.hibernate.type.SqlTypes;

/**
 * A credit sale (D74, spec §7.7, §8.3 "crédito autorizado"): an amount of an order's target (the ORDER residual or an
 * ITEMS partition) put on a customer's account. It never is money received: no {@code Payment} and no cash movement.
 * RESERVED holds the customer's limit ({@code CustomersApi.reserve} under the same id) while the target's document is
 * issued; POSTED put the debit on the ledger (with the document and the ledger entry); RELEASED gave the limit back
 * before the document was issued. Both ends are final. The id is drawn here (UUIDv7, D7): it is the reservation's and
 * the debit's source id in customers.
 */
@Entity
@Table(schema = "settlement", name = "credit_charge")
public class CreditCharge {

    static final int MAX_TEXT = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(updatable = false)
    private UUID partitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private TargetType targetType;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    /** The order's business day: the debit's day on the customer's statement and the reporting day (D85). */
    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CreditChargeStatus status;

    private UUID documentId;

    private UUID ledgerEntryId;

    @Column(nullable = false, updatable = false)
    private UUID createdByEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** Who posted it; {@code null} when the sweeper did (audited as the system). */
    private UUID postedByEmployeeId;

    private Instant postedAt;

    private UUID releasedByEmployeeId;

    private Instant releasedAt;

    @Column(length = MAX_TEXT)
    private String releaseReason;

    @Version
    private Long version;

    protected CreditCharge() {
    }

    /**
     * A RESERVED charge of {@code partitionId} ({@code null}: the order's residual) of {@code orderId}.
     *
     * @throws SettlementRuleException {@code INVALID_AMOUNT} for an amount that is not positive
     */
    public static CreditCharge reserve(UUID orderId, UUID partitionId, UUID storeId, UUID terminalId,
                                       UUID customerId, Money amount, LocalDate businessDate, UUID employeeId,
                                       Instant now) {
        if (amount.amount().signum() <= 0) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT, "A credit charge must be positive.");
        }
        CreditCharge charge = new CreditCharge();
        charge.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        charge.orderId = Objects.requireNonNull(orderId, "orderId");
        charge.partitionId = partitionId;
        charge.targetType = partitionId == null ? TargetType.ORDER : TargetType.PARTITION;
        charge.storeId = Objects.requireNonNull(storeId, "storeId");
        charge.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        charge.customerId = Objects.requireNonNull(customerId, "customerId");
        charge.amount = amount.amount();
        charge.currency = amount.currency().getCurrencyCode();
        charge.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        charge.status = CreditChargeStatus.RESERVED;
        charge.createdByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        charge.createdAt = now;
        return charge;
    }

    /** The target's document draft; set once, while RESERVED (a retry attaches the same one). */
    public void attachDocument(UUID document) {
        Objects.requireNonNull(document, "document");
        if (documentId != null && !documentId.equals(document)) {
            throw new IllegalStateException("Credit charge " + id + " has document " + documentId);
        }
        requireReserved("take a document");
        documentId = document;
    }

    /** RESERVED → POSTED: the debit {@code ledgerEntry} of its issued document is on the customer's ledger. */
    public void post(UUID ledgerEntry, UUID employeeId, Instant now) {
        requireReserved("be posted");
        if (documentId == null) {
            throw new IllegalStateException("Credit charge " + id + " has no document to post");
        }
        status = CreditChargeStatus.POSTED;
        ledgerEntryId = Objects.requireNonNull(ledgerEntry, "ledgerEntry");
        postedByEmployeeId = employeeId;
        postedAt = now;
    }

    /**
     * RESERVED → RELEASED. The caller checked that its document is not ISSUED.
     *
     * @throws SettlementRuleException {@code REASON_REQUIRED}
     */
    public void release(String reason, UUID employeeId, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new SettlementRuleException(SettlementRule.REASON_REQUIRED, "Cancelling a credit charge needs a "
                    + "reason.");
        }
        requireReserved("be cancelled");
        status = CreditChargeStatus.RELEASED;
        releasedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        releasedAt = now;
        releaseReason = reason.length() <= MAX_TEXT ? reason : reason.substring(0, MAX_TEXT);
    }

    /** Whether a retried sale repeats this charge (same customer, same amount). */
    public boolean repeats(UUID customer, Money other) {
        return customerId.equals(customer) && amount().equals(other);
    }

    private void requireReserved(String command) {
        if (status != CreditChargeStatus.RESERVED) {
            throw SettlementConflictException.illegalChargeState(id, status, command);
        }
    }

    public boolean isLive() {
        return status.isLive();
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public CreditChargeView toView() {
        return new CreditChargeView(id, orderId, targetType.name(), partitionId, customerId, amount(), status.name(),
                documentId, ledgerEntryId, createdAt, postedAt, releasedAt, releaseReason,
                version == null ? 0 : version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPartitionId() {
        return partitionId;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public CreditChargeStatus getStatus() {
        return status;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public UUID getLedgerEntryId() {
        return ledgerEntryId;
    }

    public UUID getPostedByEmployeeId() {
        return postedByEmployeeId;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }
}
