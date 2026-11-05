package com.xavier.backend.fiscal.model;

import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.exception.IllegalDocumentStateException;
import com.xavier.backend.fiscal.exception.InvalidSaleDraftException;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentSummary;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.Money;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;

/**
 * A fiscal document (spec §8.3: DRAFT → ISSUED). It holds snapshots of the company, the store and the buyer, the
 * copied lines and the tax summary per rate, and never reads the current catalog or profile again (spec §8.2).
 * Once ISSUED it and its lines are immutable, which the database enforces as well (trigger).
 */
@Entity
@Table(schema = "fiscal", name = "fiscal_document")
public class FiscalDocument {

    static final int MAX_ERROR_LENGTH = 1000;
    private static final int RATE_SCALE = 4;
    private static final int QUANTITY_SCALE = 3;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private SourceType sourceType;

    @Column(nullable = false, updatable = false)
    private UUID sourceId;

    @Column(updatable = false)
    private UUID orderId;

    /** The database's {@code coalesce(order_id, source_id of an ORDER document)}: R2 rows have no order_id. */
    @Generated
    @Column(insertable = false, updatable = false)
    private UUID orderRef;

    @Column(updatable = false)
    private LocalDate businessDate;

    private UUID customerId;

    @Column(nullable = false)
    private boolean onAccount;

    private UUID seriesId;

    @Column(length = 20)
    private String seriesCode;

    private Long number;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalGross;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalNet;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalTax;

    @Column(nullable = false, updatable = false, length = 200)
    private String companyName;

    @Column(nullable = false, updatable = false, length = 50)
    private String companyTaxId;

    @Column(nullable = false, updatable = false, length = 2)
    private String companyCountry;

    @Column(nullable = false, updatable = false, length = 20)
    private String storeCode;

    @Column(nullable = false, updatable = false, length = 120)
    private String storeName;

    @Column(nullable = false, updatable = false, length = 64)
    private String storeTimezone;

    @Column(length = 200)
    private String buyerName;

    @Column(length = 50)
    private String buyerTaxId;

    @Column(length = 300)
    private String buyerAddress;

    @Column(length = 40)
    private String adapterCode;

    private String adapterData;

    private String qrPayload;

    private Boolean nonFiscal;

    private Instant issuedAt;

    private UUID issuedByEmployeeId;

    private UUID issuedTerminalId;

    @Column(length = MAX_ERROR_LENGTH)
    private String lastError;

    private Instant lastErrorAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    @OrderBy("lineNo")
    private List<FiscalDocumentLine> lines = new ArrayList<>();

    @OneToMany(cascade = CascadeType.PERSIST)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    @OrderBy("taxRate")
    private List<FiscalDocumentTax> taxes = new ArrayList<>();

    protected FiscalDocument() {
        // JPA
    }

    /**
     * The SALE draft of an order or of an ITEMS partition (D70). {@code company} and {@code store} are snapshotted
     * now; the lines are checked (D59: gross = unit price × quantity = net + tax; totals = Σ lines) and their tax is
     * summed per rate. An ORDER draft without an explicit order belongs to its source.
     */
    public static FiscalDocument draft(DraftCommand command, CompanyProfileView company, StoreSummary store,
                                       Instant now) {
        Currency currency = company.currency();
        List<DraftLine> lines = command.lines() == null ? List.of() : command.lines();
        if (lines.isEmpty()) {
            throw new InvalidSaleDraftException("A sale document needs at least one line");
        }
        FiscalDocument document = new FiscalDocument();
        document.storeId = Objects.requireNonNull(command.storeId(), "storeId");
        document.documentType = DocumentType.SALE;
        document.status = DocumentStatus.DRAFT;
        document.sourceType = Objects.requireNonNull(command.sourceType(), "sourceType");
        document.sourceId = Objects.requireNonNull(command.sourceId(), "sourceId");
        document.orderId = orderOf(command);
        document.businessDate = command.businessDate();
        document.currency = currency.getCurrencyCode();
        document.companyName = company.name();
        document.companyTaxId = company.taxId();
        document.companyCountry = company.country();
        document.storeCode = store.code();
        document.storeName = store.name();
        document.storeTimezone = store.timezone().getId();
        document.snapshotBuyer(command.buyer());
        document.chargeTo(command.customerId(), command.onAccount());
        document.createdAt = now;
        document.updatedAt = now;

        Money gross = zero(currency);
        Money net = zero(currency);
        Money tax = zero(currency);
        Map<BigDecimal, Money[]> perRate = new TreeMap<>();
        for (DraftLine line : lines) {
            FiscalDocumentLine copied = copy(document.lines.size() + 1, line, currency);
            document.lines.add(copied);
            BigDecimal rate = rate(line.taxRatePercent());
            gross = gross.plus(line.gross());
            net = net.plus(line.net());
            tax = tax.plus(line.tax());
            Money[] sums = perRate.computeIfAbsent(rate, r -> new Money[]{zero(currency), zero(currency)});
            sums[0] = sums[0].plus(line.net());
            sums[1] = sums[1].plus(line.tax());
        }
        requireTotal("totalGross", command.totalGross(), gross, currency);
        requireTotal("totalNet", command.totalNet(), net, currency);
        requireTotal("totalTax", command.totalTax(), tax, currency);
        document.totalGross = gross.amount();
        document.totalNet = net.amount();
        document.totalTax = tax.amount();
        perRate.forEach((rate, sums) -> document.taxes.add(new FiscalDocumentTax(rate, sums[0], sums[1])));
        return document;
    }

    /**
     * A DRAFT may still receive (or lose) its buyer snapshot, customer and on-account mark, for example when the
     * operator picks the customer at close; an ISSUED document never changes.
     */
    public void amendDraft(Buyer buyer, UUID customerId, boolean onAccount, Instant now) {
        requireDraft("amend");
        Buyer before = buyer();
        UUID customerBefore = this.customerId;
        boolean onAccountBefore = this.onAccount;
        buyerName = null;
        buyerTaxId = null;
        buyerAddress = null;
        snapshotBuyer(buyer);
        chargeTo(customerId, onAccount);
        if (!Objects.equals(before, buyer()) || !Objects.equals(customerBefore, this.customerId)
                || onAccountBefore != this.onAccount) {
            updatedAt = now; // a retry with the same choice changes nothing, not even the version
        }
    }

    /**
     * DRAFT → ISSUED with the number just taken from {@code series} (under its lock). The last failure, if any, is
     * cleared: the document is no longer pending.
     */
    public void issue(FiscalSeries series, long number, Instant now, UUID employeeId, UUID terminalId,
                      String adapterCode, boolean nonFiscal, IssueResult result) {
        requireDraft("issue");
        this.status = DocumentStatus.ISSUED;
        this.seriesId = series.getId();
        this.seriesCode = series.getCode();
        this.number = number;
        this.issuedAt = now;
        this.issuedByEmployeeId = employeeId;
        this.issuedTerminalId = terminalId;
        this.adapterCode = adapterCode;
        this.nonFiscal = nonFiscal;
        this.adapterData = result.adapterData();
        this.qrPayload = result.qrPayload();
        this.lastError = null;
        this.lastErrorAt = null;
        this.updatedAt = now;
    }

    /** Keeps the draft visible as a fiscal pendency with the reason its issue failed (D50 §7.6). */
    public void recordIssueFailure(String error, Instant now) {
        requireDraft("record an issue failure on");
        String message = error == null || error.isBlank() ? "unknown error" : error.strip();
        this.lastError = message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
        this.lastErrorAt = now;
        this.updatedAt = now;
    }

    public boolean isIssued() {
        return status == DocumentStatus.ISSUED;
    }

    public DocumentView toView() {
        Currency c = Currency.getInstance(currency);
        return new DocumentView(id, storeId, documentType.name(), status.name(), sourceType.name(), sourceId,
                seriesCode, number, issuedAt, issuedByEmployeeId, companyName, companyTaxId, companyCountry,
                storeCode, storeName, buyer(), lines.stream().map(l -> l.toView(c)).toList(),
                taxes.stream().map(t -> t.toView(c)).toList(), Money.of(totalGross, c), Money.of(totalNet, c),
                Money.of(totalTax, c), adapterCode, nonFiscal, lastError, lastErrorAt, version, orderRef, businessDate,
                customerId, onAccount);
    }

    public DocumentSummary toSummary() {
        return new DocumentSummary(id, storeId, documentType.name(), status.name(), sourceId, seriesCode, number,
                issuedAt, getTotalGross(), buyerName);
    }

    /**
     * The facts reporting projects (D84). An R2 document has no business date: it falls back to the business day
     * of its issue in its store's zone, with the cutoff {@code storeCutoff} supplies (read only then, D85).
     */
    public DocumentFacts toFacts(Supplier<LocalTime> storeCutoff) {
        LocalDate day = businessDate != null ? businessDate
                : BusinessDays.of(issuedAt != null ? issuedAt : createdAt, ZoneId.of(storeTimezone), storeCutoff.get());
        DocumentView view = toView();
        return new DocumentFacts(id, storeId, documentType.name(), status.name(), sourceType.name(), sourceId,
                orderRef, day, issuedAt, issuedTerminalId, issuedByEmployeeId, customerId, onAccount, seriesCode,
                number, nonFiscal, view.totalGross(), view.totalNet(), view.totalTax(), view.lines(), view.taxes());
    }

    /** What an adapter renders; only for an ISSUED document. */
    public IssuedDocument toIssuedDocument() {
        if (!isIssued()) {
            throw new IllegalDocumentStateException("Document " + id + " is not issued");
        }
        DocumentView view = toView();
        return new IssuedDocument(id, documentType.name(), seriesCode, number, issuedAt, ZoneId.of(storeTimezone),
                companyName, companyTaxId, companyCountry, storeCode, storeName, buyer(), view.lines(), view.taxes(),
                view.totalGross(), view.totalNet(), view.totalTax(), adapterData, qrPayload, onAccount);
    }

    private Buyer buyer() {
        if (buyerName == null && buyerTaxId == null && buyerAddress == null) {
            return null;
        }
        return new Buyer(buyerName, buyerTaxId, buyerAddress);
    }

    private void snapshotBuyer(Buyer buyer) {
        if (buyer == null) {
            return;
        }
        buyerName = optionalText(buyer.name(), "buyer.name", 200);
        buyerTaxId = optionalText(buyer.taxId(), "buyer.taxId", 50);
        buyerAddress = optionalText(buyer.address(), "buyer.address", 300);
    }

    private static UUID orderOf(DraftCommand command) {
        UUID order = command.orderId();
        if (command.sourceType() == SourceType.PARTITION && order == null) {
            throw new InvalidSaleDraftException("A partition document needs its orderId");
        }
        if (command.sourceType() == SourceType.ORDER && order != null && !order.equals(command.sourceId())) {
            throw new InvalidSaleDraftException("An order document's orderId must be its sourceId");
        }
        return command.sourceType() == SourceType.ORDER ? command.sourceId() : order;
    }

    private void chargeTo(UUID customer, boolean credit) {
        if (credit && customer == null) {
            throw new InvalidSaleDraftException("An on-account document needs its customer");
        }
        this.customerId = customer;
        this.onAccount = credit;
    }

    private void requireDraft(String action) {
        if (status != DocumentStatus.DRAFT) {
            throw new IllegalDocumentStateException("Cannot " + action + " document " + id + ": it is " + status);
        }
    }

    private static FiscalDocumentLine copy(int lineNo, DraftLine line, Currency currency) {
        String where = "line " + lineNo + ": ";
        String description = optionalText(line.description(), where + "description", 200);
        if (description == null) {
            throw new InvalidSaleDraftException(where + "description is required");
        }
        if (line.quantity() == null || line.quantity().signum() <= 0) {
            throw new InvalidSaleDraftException(where + "quantity must be positive");
        }
        BigDecimal quantity = scaled(line.quantity(), QUANTITY_SCALE, where + "quantity");
        for (Money amount : new Money[]{line.unitPrice(), line.gross(), line.net(), line.tax()}) {
            if (amount == null || !amount.currency().equals(currency)) {
                throw new InvalidSaleDraftException(where + "every amount must be in " + currency);
            }
        }
        if (!line.unitPrice().times(quantity).equals(line.gross())) {
            throw new InvalidSaleDraftException(where + "gross must be unit price × quantity");
        }
        if (!line.net().plus(line.tax()).equals(line.gross())) {
            throw new InvalidSaleDraftException(where + "net + tax must equal gross");
        }
        BigDecimal rate = rate(line.taxRatePercent());
        Money expectedNet = netOf(line.gross(), rate);
        if (!line.net().equals(expectedNet)) {
            throw new InvalidSaleDraftException(where + "net must be gross / (1 + rate/100) = "
                    + expectedNet.amount() + " at rate " + rate.stripTrailingZeros().toPlainString() + " %");
        }
        return new FiscalDocumentLine(lineNo, line.sourceLineId(), description, quantity, line.unitPrice(),
                rate, line.gross(), line.net(), line.tax());
    }

    /** D59, tax-inclusive prices: {@code net = gross / (1 + rate/100)}, HALF_UP to minor units; tax = gross − net. */
    static Money netOf(Money gross, BigDecimal ratePercent) {
        return gross.divide(BigDecimal.ONE.add(ratePercent.movePointLeft(2)));
    }

    private static BigDecimal rate(BigDecimal percent) {
        if (percent == null || percent.signum() < 0) {
            throw new InvalidSaleDraftException("a tax rate must be zero or positive");
        }
        return scaled(percent, RATE_SCALE, "tax rate");
    }

    private static BigDecimal scaled(BigDecimal value, int scale, String what) {
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidSaleDraftException(what + " has more than " + scale + " decimals");
        }
    }

    private static void requireTotal(String name, Money given, Money sum, Currency currency) {
        if (given == null || !given.currency().equals(currency)) {
            throw new InvalidSaleDraftException(name + " must be in " + currency);
        }
        if (!given.equals(sum)) {
            throw new InvalidSaleDraftException(name + " " + given.amount() + " is not the sum of the lines "
                    + sum.amount());
        }
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidSaleDraftException(field + " is longer than " + maxLength + " characters");
        }
        return stripped;
    }

    private static Money zero(Currency currency) {
        return Money.of(BigDecimal.ZERO, currency);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public Long getNumber() {
        return number;
    }

    public Money getTotalGross() {
        return Money.of(totalGross, Currency.getInstance(currency));
    }

    public boolean isOnAccount() {
        return onAccount;
    }

    public Money getTotalTax() {
        return Money.of(totalTax, Currency.getInstance(currency));
    }

    public String getAdapterData() {
        return adapterData;
    }
}
