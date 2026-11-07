package com.xavier.backend.promotions.loyalty.model;

import com.xavier.backend.promotions.loyalty.model.dto.RewardView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A reward of a program version (D112): {@code pointsCost} points buy a free unit, or an amount off one unit, of an
 * item, of a family's items, or (no scope) of any item. Edited only while its version is DRAFT (database trigger).
 */
@Entity
@Table(schema = "promotions", name = "loyalty_reward")
public class LoyaltyReward {

    public static final String CODE_UK = "loyalty_reward_code_uk";
    public static final String ENTITY = "LoyaltyReward";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID versionId;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private int pointsCost;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RewardEffect effect;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    private UUID itemId;

    private UUID familyId;

    protected LoyaltyReward() {
        // JPA
    }

    public static LoyaltyReward of(UUID versionId, String code, String name, int pointsCost, RewardEffect effect,
                                   Money amount, UUID itemId, UUID familyId) {
        LoyaltyReward reward = new LoyaltyReward();
        reward.versionId = Objects.requireNonNull(versionId, "versionId");
        reward.code = code.strip();
        reward.change(name, pointsCost, effect, amount, itemId, familyId);
        return reward;
    }

    /** Its name, cost, effect and scope; the code stays. Callers check the effect's amount and the scope first. */
    public void change(String name, int pointsCost, RewardEffect effect, Money amount, UUID itemId, UUID familyId) {
        if (pointsCost <= 0) {
            throw new IllegalArgumentException("A reward costs a positive number of points");
        }
        if ((effect == RewardEffect.AMOUNT_OFF_UNIT) != (amount != null)) {
            throw new IllegalArgumentException("Only an AMOUNT_OFF_UNIT reward has an amount");
        }
        if (itemId != null && familyId != null) {
            throw new IllegalArgumentException("A reward is scoped to an item or a family, not both");
        }
        this.name = name.strip();
        this.pointsCost = pointsCost;
        this.effect = effect;
        this.amount = amount == null ? null : amount.amount();
        this.currency = amount == null ? null : amount.currency().getCurrencyCode();
        this.itemId = itemId;
        this.familyId = familyId;
    }

    /** True when the reward applies to a line of {@code lineItemId} in {@code lineFamilyId}. */
    public boolean covers(UUID lineItemId, UUID lineFamilyId) {
        if (itemId != null) {
            return itemId.equals(lineItemId);
        }
        return familyId == null || familyId.equals(lineFamilyId);
    }

    /** The unit price after the reward: 0 for a free unit, never below 0 for an amount off. */
    public Money priceAfter(Money unitPrice) {
        if (effect == RewardEffect.FREE_UNIT) {
            return Money.of(BigDecimal.ZERO, unitPrice.currency());
        }
        Money after = unitPrice.minus(amount().orElseThrow());
        return after.amount().signum() < 0 ? Money.of(BigDecimal.ZERO, unitPrice.currency()) : after;
    }

    public RewardView toView() {
        return new RewardView(id, code, name, pointsCost, effect.name(), amount().orElse(null), itemId, familyId);
    }

    public Optional<Money> amount() {
        return amount == null ? Optional.empty() : Optional.of(Money.of(amount, Currency.getInstance(currency)));
    }

    public UUID getId() {
        return id;
    }

    public UUID getVersionId() {
        return versionId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPointsCost() {
        return pointsCost;
    }

    public RewardEffect getEffect() {
        return effect;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getFamilyId() {
        return familyId;
    }
}
