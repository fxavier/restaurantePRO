package com.xavier.backend.promotions.loyalty.model;

import com.xavier.backend.promotions.EarnLine;
import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import com.xavier.backend.promotions.loyalty.model.dto.ProgramVersionView;
import com.xavier.backend.promotions.loyalty.model.dto.RewardView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A version of the company-wide loyalty program ("LoyaltyProgramVersion", D111, P45): the earning rule (points per
 * spend unit), the items and families that earn (none = every line) and, in {@link LoyaltyReward}, its rewards. It
 * changes only while DRAFT; once PUBLISHED it is a fact the ledger names (the database refuses any other change), and
 * publishing the next version retires it.
 */
@Entity
@Table(schema = "promotions", name = "loyalty_program_version")
public class LoyaltyProgramVersion {

    public static final String VERSION_NO_UK = "loyalty_program_version_no_uk";
    public static final String PUBLISHED_UK = "loyalty_program_version_published_uk";
    public static final String ENTITY = "LoyaltyProgramVersion";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProgramStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal spendUnit;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false)
    private int pointsPerUnit;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant publishedAt;

    private UUID publishedByAccountId;

    private Instant retiredAt;

    @Version
    private Long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(schema = "promotions", name = "loyalty_program_scope",
            joinColumns = @JoinColumn(name = "version_id"))
    private Set<ProgramScope> scope = new HashSet<>();

    protected LoyaltyProgramVersion() {
        // JPA
    }

    public static LoyaltyProgramVersion draft(int versionNo, Money spendUnit, int pointsPerUnit,
                                              Collection<UUID> itemIds, Collection<UUID> familyIds, Instant now) {
        LoyaltyProgramVersion draft = new LoyaltyProgramVersion();
        draft.versionNo = versionNo;
        draft.status = ProgramStatus.DRAFT;
        draft.currency = spendUnit.currency().getCurrencyCode();
        draft.createdAt = now;
        draft.applyRule(spendUnit, pointsPerUnit, itemIds, familyIds);
        return draft;
    }

    /** A DRAFT's new rule and scope; any other status is a 409 {@code LOYALTY_PROGRAM_NOT_DRAFT}. */
    public void edit(Money spendUnit, int pointsPerUnit, Collection<UUID> itemIds, Collection<UUID> familyIds) {
        requireDraft("be edited");
        applyRule(spendUnit, pointsPerUnit, itemIds, familyIds);
    }

    /** DRAFT → PUBLISHED; the caller retires the version in force first. */
    public void publish(UUID accountId, Instant now) {
        requireDraft("be published");
        status = ProgramStatus.PUBLISHED;
        publishedAt = now;
        publishedByAccountId = Objects.requireNonNull(accountId, "accountId");
    }

    /** PUBLISHED → RETIRED, when the next version is published. */
    public void retire(Instant now) {
        if (status != ProgramStatus.PUBLISHED) {
            throw LoyaltyConflictException.programNotDraft(id, status, "be retired");
        }
        status = ProgramStatus.RETIRED;
        retiredAt = now;
    }

    public void requireDraft(String action) {
        if (status != ProgramStatus.DRAFT) {
            throw LoyaltyConflictException.programNotDraft(id, status, action);
        }
    }

    /** The earning of an order's lines under this version's rule (P45). */
    public Earning earn(List<EarnLine> lines) {
        Money eligible = Money.of(BigDecimal.ZERO, currency());
        for (EarnLine line : lines) {
            if (!line.rewarded() && inScope(line)) {
                eligible = eligible.plus(line.gross());
            }
        }
        if (eligible.amount().signum() <= 0) {
            return new Earning(eligible, 0);
        }
        int units = eligible.amount().divide(spendUnit, 0, RoundingMode.FLOOR).intValueExact();
        return new Earning(eligible, Math.multiplyExact(units, pointsPerUnit));
    }

    /** No scope = every line; else the line's item or its family is in the scope. */
    private boolean inScope(EarnLine line) {
        return scope.isEmpty() || scope.contains(ProgramScope.item(line.itemId()))
                || (line.familyId() != null && scope.contains(ProgramScope.family(line.familyId())));
    }

    private void applyRule(Money spendUnit, int pointsPerUnit, Collection<UUID> itemIds, Collection<UUID> familyIds) {
        if (!spendUnit.currency().getCurrencyCode().equals(currency)) {
            throw new IllegalArgumentException("A loyalty program is in " + currency);
        }
        if (spendUnit.amount().signum() <= 0 || pointsPerUnit <= 0) {
            throw new IllegalArgumentException("A loyalty program earns a positive number of points per spend unit");
        }
        this.spendUnit = spendUnit.amount();
        this.pointsPerUnit = pointsPerUnit;
        Set<ProgramScope> next = new HashSet<>();
        itemIds.stream().map(ProgramScope::item).forEach(next::add);
        familyIds.stream().map(ProgramScope::family).forEach(next::add);
        // Replaced whole (one delete by version_id, then inserts), never row by row: each scope row has a null column.
        if (!scope.equals(next)) {
            scope = next;
        }
    }

    public ProgramVersionView toView(List<RewardView> rewards) {
        return new ProgramVersionView(id, versionNo, status.name(), spendUnit(), pointsPerUnit,
                itemIds().stream().sorted().toList(), familyIds().stream().sorted().toList(), rewards, createdAt,
                publishedAt, publishedByAccountId, retiredAt, getVersion());
    }

    public Money spendUnit() {
        return Money.of(spendUnit, currency());
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public Set<UUID> itemIds() {
        return scope.stream().map(ProgramScope::itemId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public Set<UUID> familyIds() {
        return scope.stream().map(ProgramScope::familyId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    public UUID getId() {
        return id;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public ProgramStatus getStatus() {
        return status;
    }

    public int getPointsPerUnit() {
        return pointsPerUnit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getPublishedByAccountId() {
        return publishedByAccountId;
    }

    public Instant getRetiredAt() {
        return retiredAt;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
