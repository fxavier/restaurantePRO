package com.xavier.backend.promotions.model;

import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A company-wide line-level promotion of the working copy (D102, P43), assigned to stores. It guards its kind values,
 * its validity and its scope; pricing only ever reads the frozen copy a published release holds.
 */
@Entity
@Table(schema = "promotions", name = "promotion")
public class Promotion {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * The order modes a promotion may name. Mirrors {@code orders.OrderMode} plus DELIVERY (S50); promotions may not
     * import orders (D99), so a new mode is added here too.
     */
    public static final Set<String> ORDER_MODES = Set.of("DINE_IN", "COUNTER", "TAKEAWAY", "DELIVERY");

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PromotionKind kind;

    @Column(precision = 5, scale = 2)
    private BigDecimal percent;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false)
    private int priority;

    private Instant validFrom;

    private Instant validTo;

    @Column(nullable = false)
    private boolean membersOnly;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] modes;

    @Column(nullable = false)
    private boolean active;

    @ElementCollection
    @CollectionTable(schema = "promotions", name = "promotion_scope", joinColumns = @JoinColumn(name = "promotion_id"))
    @OrderColumn(name = "position")
    private List<ScopeEntry> scope = new ArrayList<>();

    @ElementCollection
    @CollectionTable(schema = "promotions", name = "promotion_window", joinColumns = @JoinColumn(name = "promotion_id"))
    @OrderColumn(name = "position")
    private List<TimeWindow> windows = new ArrayList<>();

    @ElementCollection
    @CollectionTable(schema = "promotions", name = "promotion_store", joinColumns = @JoinColumn(name = "promotion_id"))
    @Column(name = "store_id", nullable = false)
    private Set<UUID> storeIds = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Promotion() {
        // JPA
    }

    /** The terms of a promotion; {@code amount} is null for a percent, {@code modes} null for every mode. */
    public record Terms(String name, PromotionKind kind, BigDecimal percent, Money amount, int priority,
                        Instant validFrom, Instant validTo, boolean membersOnly, List<String> modes, boolean active) {
    }

    public static Promotion create(String code, Terms terms, Instant now) {
        Promotion promotion = new Promotion();
        promotion.code = code.strip();
        promotion.createdAt = now;
        promotion.apply(terms, now);
        return promotion;
    }

    public void change(Terms terms, Instant now) {
        apply(terms, now);
    }

    private void apply(Terms terms, Instant now) {
        requireKindValues(terms);
        if (terms.validFrom() != null && terms.validTo() != null && !terms.validFrom().isBefore(terms.validTo())) {
            throw PromotionRuleException.invalid("The validity must start before it ends: " + terms.validFrom()
                    + " is not before " + terms.validTo() + ".");
        }
        if (terms.modes() != null && terms.modes().isEmpty()) {
            throw PromotionRuleException.invalid("List at least one order mode, or none for every mode.");
        }
        if (terms.modes() != null && !ORDER_MODES.containsAll(terms.modes())) {
            throw PromotionRuleException.invalid("The order modes " + terms.modes() + " are not all among "
                    + ORDER_MODES.stream().sorted().toList() + ".");
        }
        name = terms.name().strip();
        kind = terms.kind();
        percent = terms.percent();
        amount = terms.amount() == null ? null : terms.amount().amount();
        currency = terms.amount() == null ? null : terms.amount().currency().getCurrencyCode();
        priority = terms.priority();
        validFrom = terms.validFrom();
        validTo = terms.validTo();
        membersOnly = terms.membersOnly();
        modes = terms.modes() == null ? null : new LinkedHashSet<>(terms.modes()).toArray(String[]::new);
        active = terms.active();
        updatedAt = now;
    }

    private static void requireKindValues(Terms terms) {
        boolean valid = switch (terms.kind()) {
            case PERCENT_OFF -> terms.amount() == null && terms.percent() != null && terms.percent().signum() > 0
                    && terms.percent().compareTo(HUNDRED) <= 0 && terms.percent().stripTrailingZeros().scale() <= 2;
            case AMOUNT_OFF_UNIT -> terms.percent() == null && terms.amount() != null
                    && terms.amount().amount().signum() > 0;
            case FIXED_UNIT_PRICE -> terms.percent() == null && terms.amount() != null
                    && terms.amount().amount().signum() >= 0;
        };
        if (!valid) {
            throw PromotionRuleException.invalid(switch (terms.kind()) {
                case PERCENT_OFF -> "A PERCENT_OFF promotion needs a percent above 0 and up to 100 (two decimals at "
                        + "most) and no amount.";
                case AMOUNT_OFF_UNIT -> "An AMOUNT_OFF_UNIT promotion needs an amount above 0 and no percent.";
                case FIXED_UNIT_PRICE -> "A FIXED_UNIT_PRICE promotion needs an amount of 0 or more and no percent.";
            });
        }
    }

    /** Replaces the scope; it is never empty. */
    public void replaceScope(Collection<UUID> itemIds, Collection<UUID> variantIds, Collection<UUID> familyIds,
                             Instant now) {
        List<ScopeEntry> entries = new ArrayList<>();
        new LinkedHashSet<>(itemIds).forEach(id -> entries.add(ScopeEntry.item(id)));
        new LinkedHashSet<>(variantIds).forEach(id -> entries.add(ScopeEntry.variant(id)));
        new LinkedHashSet<>(familyIds).forEach(id -> entries.add(ScopeEntry.family(id)));
        if (entries.isEmpty()) {
            throw PromotionRuleException.invalid("A promotion's scope names at least one item, variant or family.");
        }
        scope = entries;
        updatedAt = now;
    }

    /** Replaces the windows; an empty list means at any time. */
    public void replaceWindows(List<PromotionSnapshot.Window> requested, Instant now) {
        List<TimeWindow> replaced = new ArrayList<>();
        for (PromotionSnapshot.Window window : requested) {
            if (window.daysOfWeek().isEmpty() || window.daysOfWeek().stream().anyMatch(d -> d == null || d < 1 || d > 7)) {
                throw PromotionRuleException.invalid("A window lists days of the week from 1 (Monday) to 7 (Sunday).");
            }
            if (window.start().equals(window.end())) {
                throw PromotionRuleException.invalid("A window cannot start and end at the same time "
                        + window.start() + ".");
            }
            replaced.add(new TimeWindow(window.daysOfWeek(), window.start(), window.end()));
        }
        windows = replaced;
        updatedAt = now;
    }

    public void replaceStores(Set<UUID> stores, Instant now) {
        storeIds = new HashSet<>(stores);
        updatedAt = now;
    }

    public boolean hasEmptyScope() {
        return scope.isEmpty();
    }

    public PromotionSnapshot.Scope toScope() {
        return new PromotionSnapshot.Scope(
                scope.stream().map(ScopeEntry::getItemId).filter(Objects::nonNull).toList(),
                scope.stream().map(ScopeEntry::getVariantId).filter(Objects::nonNull).toList(),
                scope.stream().map(ScopeEntry::getFamilyId).filter(Objects::nonNull).toList());
    }

    public PromotionSnapshot.Promotion toSnapshot() {
        return new PromotionSnapshot.Promotion(id, code, name, kind.name(),
                percent == null ? null : percent.stripTrailingZeros().toPlainString(),
                amount == null ? null : getAmount().amount().toPlainString(), priority, validFrom, validTo,
                membersOnly, modes == null ? null : List.of(modes), toScope(),
                windows.stream().map(TimeWindow::toWindow).toList());
    }

    public PromotionView toView() {
        return new PromotionView(id, code, name, kind.name(), percent,
                amount == null ? null : getAmount().amount(), currency, priority, validFrom, validTo, membersOnly,
                modes == null ? null : List.of(modes), active, toScope(),
                windows.stream().map(TimeWindow::toWindow).toList(), storeIds.stream().sorted().toList(), version);
    }

    /** The amount in its currency, or null for a percent. */
    public Money getAmount() {
        return amount == null ? null : Money.of(amount, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public boolean isActive() {
        return active;
    }

    public Set<UUID> getStoreIds() {
        return Set.copyOf(storeIds);
    }

    public Long getVersion() {
        return version;
    }
}
