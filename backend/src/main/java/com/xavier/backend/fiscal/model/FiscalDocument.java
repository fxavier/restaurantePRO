package com.xavier.backend.fiscal.model;

import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.exception.DraftMismatchException;
import com.xavier.backend.fiscal.exception.IllegalDocumentStateException;
import com.xavier.backend.fiscal.exception.InvalidSaleDraftException;
import com.xavier.backend.fiscal.exception.PaymentSnapshotMismatchException;
import com.xavier.backend.fiscal.exception.PaymentSnapshotMissingException;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentFacts;
import com.xavier.backend.fiscal.model.dto.DocumentSummary;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
    /** The D68-amendment bound of an allocated line, in minor units (settlement's guard keeps every share inside). */
    private static final int ALLOCATED_GROSS_UNITS = 1;
    private static final int ALLOCATED_NET_UNITS = 2;

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

    /** D68 amendment: the lines carry settlement's allocated shares, checked within a bound (see {@link #copy}). */
    @Column(nullable = false, updatable = false)
    private boolean amountsAllocated;

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

    /** D97: the payment snapshot, recorded on the DRAFT by settlement just before its issue; null until then. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PaymentStatus paymentStatus;

    @Column(precision = 19, scale = 4)
    private BigDecimal paidTotal;

    @Column(precision = 19, scale = 4)
    private BigDecimal creditTotal;

    @Column(precision = 19, scale = 4)
    private BigDecimal outstandingTotal;

    @ElementCollection
    @CollectionTable(schema = "fiscal", name = "fiscal_document_payment",
            joinColumns = @JoinColumn(name = "document_id"))
    @OrderBy("position")
    private List<FiscalDocumentPayment> payments = new ArrayList<>();

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
        document.amountsAllocated = command.amountsAllocated();
        document.createdAt = now;
        document.updatedAt = now;

        Money gross = zero(currency);
        Money net = zero(currency);
        Money tax = zero(currency);
        Map<BigDecimal, Money[]> perRate = new TreeMap<>();
        Map<BigDecimal, Set<String>> codesPerRate = new TreeMap<>();
        for (DraftLine line : lines) {
            FiscalDocumentLine copied = copy(document.lines.size() + 1, line, currency, command.amountsAllocated());
            document.lines.add(copied);
            BigDecimal rate = rate(line.taxRatePercent());
            gross = gross.plus(line.gross());
            net = net.plus(line.net());
            tax = tax.plus(line.tax());
            Money[] sums = perRate.computeIfAbsent(rate, r -> new Money[]{zero(currency), zero(currency)});
            sums[0] = sums[0].plus(line.net());
            sums[1] = sums[1].plus(line.tax());
            Set<String> codes = codesPerRate.computeIfAbsent(rate, r -> new TreeSet<>());
            if (line.taxCode() != null) {
                codes.add(line.taxCode());
            }
        }
        requireTotal("totalGross", command.totalGross(), gross, currency);
        requireTotal("totalNet", command.totalNet(), net, currency);
        requireTotal("totalTax", command.totalTax(), tax, currency);
        document.totalGross = gross.amount();
        document.totalNet = net.amount();
        document.totalTax = tax.amount();
        perRate.forEach((rate, sums) -> document.taxes.add(
                new FiscalDocumentTax(taxCodeOf(codesPerRate.get(rate)), rate, sums[0], sums[1])));
        return document;
    }

    /**
     * The same source drafted again (D70): the command must carry this draft's lines and totals (409
     * {@code DRAFT_MISMATCH} otherwise). A {@code null} buyer or customer keeps the stored one and a given one replaces
     * it. On account is sticky: it may be set (with its customer) but never undone, nor moved to another customer.
     */
    public void amendDraft(DraftCommand command, Instant now) {
        requireDraft("amend");
        if (!sameContent(command)) {
            throw DraftMismatchException.lines(id);
        }
        if (onAccount && (!command.onAccount()
                || (command.customerId() != null && !command.customerId().equals(customerId)))) {
            throw DraftMismatchException.onAccount(id);
        }
        Buyer before = buyer();
        UUID customerBefore = customerId;
        boolean onAccountBefore = onAccount;
        if (command.buyer() != null) {
            buyerName = null;
            buyerTaxId = null;
            buyerAddress = null;
            snapshotBuyer(command.buyer());
        }
        // on account needs the command's own customer (422 otherwise); a paid sale keeps its customer unless given
        chargeTo(command.onAccount() || command.customerId() != null ? command.customerId() : customerId,
                command.onAccount());
        if (onAccountBefore != onAccount) {
            clearPayments(); // D97 rule 5: a snapshot taken before the credit sale no longer fits; issue refreshes it
        }
        if (!Objects.equals(before, buyer()) || !Objects.equals(customerBefore, customerId)
                || onAccountBefore != onAccount) {
            updatedAt = now; // a retry with the same choice changes nothing, not even the version
        }
    }

    /**
     * DRAFT only: the credit sale that marked it on account was cancelled before issue (D74), so the draft is a paid
     * sale again, with no customer. The only way the mark is undone; {@link #amendDraft} keeps it sticky.
     */
    public void clearOnAccount(Instant now) {
        requireDraft("clear the on-account mark of");
        if (onAccount || customerId != null || paymentStatus != null) {
            onAccount = false;
            customerId = null;
            clearPayments(); // D97: the cancelled credit was in it; issue refuses until settlement refreshes it
            updatedAt = now;
        }
    }

    /**
     * DRAFT only (D97 rule 4): replaces the payment snapshot. It must account for the whole gross ({@code paid + credit
     * + outstanding = gross}, none negative, every method positive), carry the status {@link PaymentStatus#of} gives,
     * and have credit exactly when the draft is on account (rule 5). Recording the same snapshot again changes
     * nothing, not even the version.
     *
     * @throws PaymentSnapshotMismatchException otherwise
     */
    public void recordPayments(PaymentSnapshot snapshot, Instant now) {
        requireDraft("record the payments of");
        Currency c = Currency.getInstance(currency);
        Money gross = Money.of(totalGross, c);
        requireCurrency(snapshot, c);
        Money paid = snapshot.paid();
        if (snapshot.methods().stream().anyMatch(m -> m.amount().amount().signum() <= 0)) {
            throw new PaymentSnapshotMismatchException(id, "every paid method must be positive");
        }
        if (snapshot.credit().amount().signum() < 0 || snapshot.outstanding().amount().signum() < 0) {
            throw new PaymentSnapshotMismatchException(id, "credit and outstanding cannot be negative");
        }
        if (!paid.plus(snapshot.credit()).plus(snapshot.outstanding()).equals(gross)) {
            throw new PaymentSnapshotMismatchException(id, "paid " + paid.amount() + " + credit "
                    + snapshot.credit().amount() + " + outstanding " + snapshot.outstanding().amount()
                    + " is not the gross " + gross.amount());
        }
        if ((snapshot.credit().amount().signum() > 0) != onAccount) {
            throw new PaymentSnapshotMismatchException(id, onAccount ? "the document is on account but has no credit"
                    : "the document is not on account but has credit " + snapshot.credit().amount());
        }
        if (snapshot.status() != PaymentStatus.of(gross, paid, snapshot.credit())) {
            throw new PaymentSnapshotMismatchException(id, "its status " + snapshot.status() + " is not "
                    + PaymentStatus.of(gross, paid, snapshot.credit()));
        }
        if (paymentSnapshot().filter(snapshot::equals).isPresent()) {
            return;
        }
        paymentStatus = snapshot.status();
        paidTotal = paid.amount();
        creditTotal = snapshot.credit().amount();
        outstandingTotal = snapshot.outstanding().amount();
        payments.clear();
        for (PaymentSnapshot.PaidAmount method : snapshot.methods()) {
            payments.add(new FiscalDocumentPayment(payments.size() + 1, method));
        }
        updatedAt = now;
    }

    /** The payment snapshot, if recorded (a document issued before D97 has none). */
    public Optional<PaymentSnapshot> paymentSnapshot() {
        if (paymentStatus == null) {
            return Optional.empty();
        }
        Currency c = Currency.getInstance(currency);
        return Optional.of(new PaymentSnapshot(paymentStatus, payments.stream().map(p -> p.toView(c)).toList(),
                Money.of(creditTotal, c), Money.of(outstandingTotal, c)));
    }

    /** @throws PaymentSnapshotMissingException for a DRAFT settlement has not recorded the payments of (D97). */
    public void requirePaymentSnapshot() {
        if (paymentStatus == null) {
            throw new PaymentSnapshotMissingException();
        }
    }

    private void clearPayments() {
        paymentStatus = null;
        paidTotal = null;
        creditTotal = null;
        outstandingTotal = null;
        payments.clear();
    }

    private void requireCurrency(PaymentSnapshot snapshot, Currency c) {
        boolean other = !snapshot.credit().currency().equals(c) || !snapshot.outstanding().currency().equals(c)
                || snapshot.methods().stream().anyMatch(m -> !m.amount().currency().equals(c));
        if (other) {
            throw new PaymentSnapshotMismatchException(id, "every amount must be in " + c);
        }
    }

    private static String taxCodeOf(Set<String> codes) {
        return codes.isEmpty() ? null : String.join("+", codes);
    }

    private boolean sameContent(DraftCommand command) {
        Currency c = Currency.getInstance(currency);
        List<DraftLine> given = command.lines() == null ? List.of() : command.lines();
        if (command.amountsAllocated() != amountsAllocated || given.size() != lines.size()
                || !Money.of(totalGross, c).equals(command.totalGross())
                || !Money.of(totalNet, c).equals(command.totalNet())
                || !Money.of(totalTax, c).equals(command.totalTax())) {
            return false;
        }
        for (int i = 0; i < given.size(); i++) {
            if (!lines.get(i).sameAs(given.get(i), c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * DRAFT → ISSUED with the number just taken from {@code series} (under its lock). The last failure, if any, is
     * cleared: the document is no longer pending.
     */
    public void issue(FiscalSeries series, long number, Instant now, UUID employeeId, UUID terminalId,
                      String adapterCode, boolean nonFiscal, IssueResult result) {
        requireDraft("issue");
        requirePaymentSnapshot();
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
                customerId, onAccount, paymentSnapshot().orElse(null));
    }

    public DocumentSummary toSummary() {
        return new DocumentSummary(id, storeId, documentType.name(), status.name(), sourceId, seriesCode, number,
                issuedAt, getTotalGross(), buyerName);
    }

    /**
     * The facts reporting projects (D84). An R2 document has no business date: it falls back to the business day
     * of its issue in the store's CURRENT zone and cutoff (D85), the same rule as the backfill listing; the
     * snapshotted {@code store_timezone} is only what was printed. {@code currentStore} is read only then.
     */
    public DocumentFacts toFacts(Supplier<StoreSummary> currentStore) {
        LocalDate day = businessDate;
        if (day == null) {
            StoreSummary store = currentStore.get();
            day = BusinessDays.of(issuedAt != null ? issuedAt : createdAt, store.timezone(),
                    store.businessDayCutoff());
        }
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
                view.totalGross(), view.totalNet(), view.totalTax(), adapterData, qrPayload, onAccount,
                view.payments());
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

    /**
     * D59 for an unallocated line: gross = round(unitPrice × quantity) and net = round(gross / (1 + rate/100))
     * exactly. An allocated line (D68 amendment) carries a share whose rounding was distributed by settlement, so its
     * gross may be 1 minor unit and its net 2 minor units away from those values; net + tax = gross stays exact and
     * no amount is negative.
     */
    private static FiscalDocumentLine copy(int lineNo, DraftLine line, Currency currency, boolean allocated) {
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
        if (!line.net().plus(line.tax()).equals(line.gross())) {
            throw new InvalidSaleDraftException(where + "net + tax must equal gross");
        }
        BigDecimal rate = rate(line.taxRatePercent());
        Money expectedGross = line.unitPrice().times(quantity);
        Money expectedNet = netOf(line.gross(), rate);
        if (allocated) {
            requireAllocated(where, line, expectedGross, expectedNet, currency);
            return new FiscalDocumentLine(lineNo, line.sourceLineId(), description, quantity, line.unitPrice(),
                    rate, line.gross(), line.net(), line.tax());
        }
        if (!expectedGross.equals(line.gross())) {
            throw new InvalidSaleDraftException(where + "gross must be unit price × quantity");
        }
        if (!line.net().equals(expectedNet)) {
            throw new InvalidSaleDraftException(where + "net must be gross / (1 + rate/100) = "
                    + expectedNet.amount() + " at rate " + rate.stripTrailingZeros().toPlainString() + " %");
        }
        return new FiscalDocumentLine(lineNo, line.sourceLineId(), description, quantity, line.unitPrice(),
                rate, line.gross(), line.net(), line.tax());
    }

    private static void requireAllocated(String where, DraftLine line, Money expectedGross, Money expectedNet,
                                         Currency currency) {
        if (line.gross().amount().signum() < 0 || line.net().amount().signum() < 0
                || line.tax().amount().signum() < 0) {
            throw new InvalidSaleDraftException(where + "an allocated amount cannot be negative");
        }
        if (minorUnits(line.gross().minus(expectedGross), currency) > ALLOCATED_GROSS_UNITS) {
            throw new InvalidSaleDraftException(where + "an allocated gross must be within " + ALLOCATED_GROSS_UNITS
                    + " minor unit of unit price × quantity = " + expectedGross.amount());
        }
        if (minorUnits(line.net().minus(expectedNet), currency) > ALLOCATED_NET_UNITS) {
            throw new InvalidSaleDraftException(where + "an allocated net must be within " + ALLOCATED_NET_UNITS
                    + " minor units of gross / (1 + rate/100) = " + expectedNet.amount());
        }
    }

    private static int minorUnits(Money difference, Currency currency) {
        return difference.amount().abs().movePointRight(currency.getDefaultFractionDigits()).intValueExact();
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
