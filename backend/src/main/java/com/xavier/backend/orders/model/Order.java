package com.xavier.backend.orders.model;

import com.xavier.backend.orders.BillingLock;
import com.xavier.backend.orders.OrderLifecycle;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.exception.OrderConflictException;
import com.xavier.backend.orders.exception.OrderRuleException;
import com.xavier.backend.orders.model.dto.OrderSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * An order (spec §8.1 {@code order_header}; r2-decisions §4 orders). Lifecycle OPEN → CLOSED (settlement only) or
 * CANCELLED (S14); the {@link BillingLock} is independent of it (D49). Every confirmed change (a round, a lock, the
 * close) changes a column, so {@code @Version} increments and a concurrent writer that read the old version loses
 * with 409 (D49, D50 §7.1). Drafts are separate rows and never touch the order's version.
 */
@Entity
@Table(schema = "orders", name = "order_header")
public class Order {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private long orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private OrderMode mode;

    private UUID tableId;

    /** Set once from dining when the table is occupied; a display value, so it does not move the version. */
    @Column(length = 20)
    @OptimisticLock(excluded = true)
    private String tableCode;

    private Integer guests;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderLifecycle lifecycle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BillingLock billingLock;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDay;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    @Column(nullable = false, updatable = false)
    private UUID openedByEmployeeId;

    private Instant closedAt;

    private UUID closedByEmployeeId;

    @Column(nullable = false)
    private int nextBatchNo;

    @Version
    private long version;

    protected Order() {
        // JPA
    }

    /**
     * Opened by opening its table; dining's occupation is created in the same transaction and then gives the table's
     * code ({@link #seatedAt}).
     */
    public static Order dineIn(UUID storeId, UUID terminalId, long orderNumber, UUID tableId, int guests,
                               LocalDate businessDay, Currency currency, UUID employeeId, Instant now) {
        Order order = open(OrderMode.DINE_IN, storeId, terminalId, orderNumber, guests, businessDay, currency,
                employeeId, now);
        order.tableId = Objects.requireNonNull(tableId, "tableId");
        return order;
    }

    /** The code of the table this DINE_IN order occupies, as dining reports it (tickets and screens show it). */
    public void seatedAt(UUID tableId, String tableCode) {
        if (!Objects.equals(this.tableId, tableId)) {
            throw new IllegalArgumentException("Order " + id + " does not sit at table " + tableId);
        }
        this.tableCode = Objects.requireNonNull(tableCode, "tableCode");
    }

    /**
     * A COUNTER or TAKEAWAY order (P15), with no table.
     *
     * @throws OrderRuleException {@code DINE_IN_REQUIRES_TABLE} for {@code DINE_IN}
     */
    public static Order walkIn(OrderMode mode, UUID storeId, UUID terminalId, long orderNumber, Integer guests,
                               LocalDate businessDay, Currency currency, UUID employeeId, Instant now) {
        if (mode == OrderMode.DINE_IN) {
            throw OrderRuleException.dineInRequiresTable();
        }
        return open(mode, storeId, terminalId, orderNumber, guests, businessDay, currency, employeeId, now);
    }

    private static Order open(OrderMode mode, UUID storeId, UUID terminalId, long orderNumber, Integer guests,
                              LocalDate businessDay, Currency currency, UUID employeeId, Instant now) {
        if (guests != null && guests < 1) {
            throw new IllegalArgumentException("guests must be positive, was " + guests);
        }
        Order order = new Order();
        order.mode = Objects.requireNonNull(mode, "mode");
        order.storeId = Objects.requireNonNull(storeId, "storeId");
        order.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        order.orderNumber = orderNumber;
        order.guests = guests;
        order.businessDay = Objects.requireNonNull(businessDay, "businessDay");
        order.currency = currency.getCurrencyCode();
        order.openedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        order.openedAt = Objects.requireNonNull(now, "now");
        order.lifecycle = OrderLifecycle.OPEN;
        order.billingLock = BillingLock.NONE;
        order.nextBatchNo = 1;
        return order;
    }

    /**
     * New lines and rounds need an OPEN order that is not frozen for its invoice.
     *
     * @throws OrderConflictException {@code ORDER_NOT_OPEN} (409)
     * @throws OrderRuleException     {@code ORDER_FROZEN} (422)
     */
    public void requireChangeable() {
        requireOpen();
        if (billingLock == BillingLock.INVOICE) {
            throw OrderRuleException.frozen(id);
        }
    }

    /**
     * A subtotal (a non-fiscal bill) needs an OPEN order whose document does not exist yet (P27): the POS reprints
     * the document as a copy instead.
     *
     * @throws OrderConflictException {@code ORDER_NOT_OPEN} (409)
     * @throws OrderRuleException     {@code DOCUMENT_ISSUED} (422)
     */
    public void requireSubtotalPrintable() {
        requireOpen();
        if (billingLock == BillingLock.INVOICE) {
            throw OrderRuleException.documentIssued(id);
        }
    }

    /** Takes the next round number; the order's version increments with it. */
    public int confirmBatch() {
        requireChangeable();
        return nextBatchNo++;
    }

    /**
     * Voids and outgoing transfers take quantities off the bill: refused once a payment is under way (D49, P13).
     *
     * @throws OrderConflictException {@code ORDER_NOT_OPEN} (409)
     * @throws OrderRuleException     {@code ORDER_FROZEN}, {@code ORDER_IN_SETTLEMENT} (422)
     */
    public void requireBillReducible() {
        requireChangeable();
        if (billingLock == BillingLock.PAYMENT) {
            throw OrderRuleException.inSettlement(id);
        }
    }

    /**
     * OPEN → CANCELLED (r2-decisions §4): only an order that bills nothing and has no payment under way.
     *
     * @param nothingBillable every confirmed line has net quantity 0
     * @throws OrderRuleException {@code ORDER_NOT_EMPTY}, {@code ORDER_IN_SETTLEMENT}, {@code ORDER_FROZEN} (422)
     */
    public void cancel(boolean nothingBillable, UUID employeeId, Instant now) {
        requireBillReducible();
        if (!nothingBillable) {
            throw OrderRuleException.notEmpty(id);
        }
        lifecycle = OrderLifecycle.CANCELLED;
        closedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        closedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Seats the order at {@code toTableId} (a table move). The old code is dropped at once and the new one comes from
     * dining's moved occupation ({@link #seatedAt}) in the same transaction, so table and code commit together.
     *
     * @throws OrderRuleException {@code ORDER_HAS_NO_TABLE} for COUNTER and TAKEAWAY, {@code ORDER_FROZEN}
     */
    public void moveTo(UUID toTableId) {
        requireChangeable();
        if (mode != OrderMode.DINE_IN) {
            throw OrderRuleException.hasNoTable(id);
        }
        tableId = Objects.requireNonNull(toTableId, "toTableId");
        tableCode = null;
    }

    /** NONE → PAYMENT (D49). @return {@code false} when already locked (PAYMENT or INVOICE): nothing changed */
    public boolean lockForPayment() {
        requireOpen();
        if (billingLock != BillingLock.NONE) {
            return false;
        }
        billingLock = BillingLock.PAYMENT;
        return true;
    }

    /** PAYMENT → NONE. The INVOICE lock is never released in R2. @return {@code false} when nothing changed */
    public boolean releasePaymentLock() {
        if (billingLock != BillingLock.PAYMENT) {
            return false;
        }
        billingLock = BillingLock.NONE;
        return true;
    }

    /**
     * → INVOICE (D49): every later change is refused. Already frozen is a no-op even once CLOSED: a D63 retry of the
     * document step may arrive after another request closed the order.
     *
     * @return {@code false} when already frozen
     */
    public boolean freezeForInvoice() {
        if (billingLock == BillingLock.INVOICE) {
            return false;
        }
        requireOpen();
        billingLock = BillingLock.INVOICE;
        return true;
    }

    /** OPEN → CLOSED. Settlement has checked the balance and the document (D50 §7.8). */
    public void close(UUID employeeId, Instant now) {
        requireOpen();
        lifecycle = OrderLifecycle.CLOSED;
        closedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        closedAt = Objects.requireNonNull(now, "now");
    }

    private void requireOpen() {
        if (lifecycle != OrderLifecycle.OPEN) {
            throw OrderConflictException.notOpen(id);
        }
    }

    public OrderSummary toSummary() {
        return new OrderSummary(id, orderNumber, mode, tableId, tableCode, guests, lifecycle, billingLock, businessDay,
                openedAt, openedByEmployeeId, getVersion());
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public long getOrderNumber() {
        return orderNumber;
    }

    public OrderMode getMode() {
        return mode;
    }

    public UUID getTableId() {
        return tableId;
    }

    public String getTableCode() {
        return tableCode;
    }

    public Integer getGuests() {
        return guests;
    }

    public OrderLifecycle getLifecycle() {
        return lifecycle;
    }

    public BillingLock getBillingLock() {
        return billingLock;
    }

    public LocalDate getBusinessDay() {
        return businessDay;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public UUID getOpenedByEmployeeId() {
        return openedByEmployeeId;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public long getVersion() {
        return version;
    }
}
