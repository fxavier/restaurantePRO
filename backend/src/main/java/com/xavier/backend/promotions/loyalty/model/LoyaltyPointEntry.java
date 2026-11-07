package com.xavier.backend.promotions.loyalty.model;

import com.xavier.backend.promotions.RedeemCommand;
import com.xavier.backend.promotions.loyalty.model.dto.EntryView;
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
 * A fact of a member's points ledger ("LoyaltyPointLedger" entry, D110): whole points, positive for a lot (an earning,
 * a returned redemption, a positive adjustment), negative for a debit, which is allocated to the lots it consumes. The
 * database refuses UPDATE and DELETE, and {@code UNIQUE(source_type, source_id, kind)} keeps one entry per source: a
 * repeat adds nothing. A correction is another entry that names the one it reverses.
 */
@Entity
@Immutable
@Table(schema = "promotions", name = "loyalty_point_entry")
public class LoyaltyPointEntry {

    public static final String SOURCE_UK = "loyalty_point_entry_source_uk";
    public static final String REVERSES_UK = "loyalty_point_entry_reverses_uk";
    public static final String ENTITY = "LoyaltyPointEntry";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private EntryKind kind;

    @Column(nullable = false, updatable = false)
    private int points;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 12)
    private SourceType sourceType;

    @Column(nullable = false, updatable = false)
    private UUID sourceId;

    @Column(updatable = false)
    private UUID reversesEntryId;

    @Column(updatable = false)
    private UUID programVersionId;

    @Column(updatable = false)
    private UUID rewardId;

    @Column(updatable = false)
    private UUID orderId;

    @Column(updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID voidId;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal eligibleGross;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal newUnitPrice;

    @Column(updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false)
    private UUID storeId;

    @Column(updatable = false)
    private LocalDate businessDate;

    @Column(updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected LoyaltyPointEntry() {
        // JPA
    }

    private static LoyaltyPointEntry of(UUID customerId, EntryKind kind, int points, SourceType sourceType,
                                        UUID sourceId, String actorKind, UUID actorId, Instant now) {
        LoyaltyPointEntry entry = new LoyaltyPointEntry();
        entry.customerId = Objects.requireNonNull(customerId, "customerId");
        entry.kind = kind;
        entry.points = points;
        entry.sourceType = sourceType;
        entry.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        entry.actorKind = actorKind;
        entry.actorId = actorId;
        entry.occurredAt = now;
        return entry;
    }

    /** The points an order earned when it closed (D111), under {@code programVersionId}. */
    public static LoyaltyPointEntry earn(UUID customerId, UUID orderId, int points, UUID programVersionId,
                                         Money eligibleGross, UUID storeId, LocalDate businessDate,
                                         String actorKind, UUID actorId, Instant now) {
        if (points <= 0) {
            throw new IllegalArgumentException("An earning has positive points");
        }
        LoyaltyPointEntry entry = of(customerId, EntryKind.EARN, points, SourceType.ORDER, orderId, actorKind,
                actorId, now);
        entry.orderId = orderId;
        entry.programVersionId = programVersionId;
        entry.eligibleGross = eligibleGross.amount();
        entry.currency = eligibleGross.currency().getCurrencyCode();
        entry.storeId = storeId;
        entry.businessDate = businessDate;
        return entry;
    }

    /** A reward redeemed on one unit of {@code cmd.sourceLineId()} (D112): {@code reward.pointsCost} points spent. */
    public static LoyaltyPointEntry redeem(RedeemCommand cmd, LoyaltyReward reward, Money newUnitPrice,
                                           String actorKind, UUID actorId, Instant now) {
        LoyaltyPointEntry entry = of(cmd.customerId(), EntryKind.REDEEM, -reward.getPointsCost(),
                SourceType.ORDER_LINE, cmd.sourceLineId(), actorKind, actorId, now);
        entry.programVersionId = reward.getVersionId();
        entry.rewardId = reward.getId();
        entry.orderId = cmd.orderId();
        entry.itemId = cmd.itemId();
        entry.unitPrice = cmd.currentUnitPrice().amount();
        entry.newUnitPrice = newUnitPrice.amount();
        entry.currency = cmd.currentUnitPrice().currency().getCurrencyCode();
        entry.storeId = cmd.storeId();
        entry.businessDate = cmd.businessDate();
        return entry;
    }

    /** The redemption {@code this} given back because its line was voided ({@code voidId}). */
    public LoyaltyPointEntry reverseRedemption(UUID voidId, String actorKind, UUID actorId, Instant now) {
        if (kind != EntryKind.REDEEM) {
            throw new IllegalStateException("Only a REDEEM entry is reversed as a redemption");
        }
        LoyaltyPointEntry reversal = of(customerId, EntryKind.REDEEM_REVERSAL, -points, SourceType.ORDER_LINE,
                sourceId, actorKind, actorId, now);
        reversal.reversesEntryId = id;
        reversal.voidId = Objects.requireNonNull(voidId, "voidId");
        reversal.orderId = orderId;
        reversal.storeId = storeId;
        reversal.businessDate = businessDate;
        return reversal;
    }

    /** A manual adjustment with its reason (§8.4), in a store's name; {@code adjustmentId} is its own source id. */
    public static LoyaltyPointEntry adjustment(UUID customerId, UUID adjustmentId, int points, String reason,
                                               UUID storeId, String actorKind, UUID actorId, Instant now) {
        if (points == 0 || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("An adjustment has non-zero points and a reason");
        }
        LoyaltyPointEntry entry = of(customerId, EntryKind.ADJUSTMENT, points, SourceType.ADJUSTMENT, adjustmentId,
                actorKind, actorId, now);
        entry.reason = reason.strip();
        entry.storeId = Objects.requireNonNull(storeId, "storeId");
        return entry;
    }

    /** True when a repeated redemption of the same line carries the same values. */
    public boolean repeats(RedeemCommand cmd, LoyaltyReward redeemed) {
        return customerId.equals(cmd.customerId()) && redeemed.getId().equals(rewardId)
                && redeemed.getCode().equals(cmd.rewardCode())
                && orderId.equals(cmd.orderId()) && Objects.equals(itemId, cmd.itemId())
                && storeId.equals(cmd.storeId()) && businessDate.equals(cmd.businessDate())
                && money(unitPrice).equals(cmd.currentUnitPrice());
    }

    /** True when a repeated earning of the same order carries the same facts (the instant may differ). */
    public boolean repeats(UUID customerId, UUID storeId, LocalDate businessDate, Money eligibleGross) {
        return this.customerId.equals(customerId) && this.storeId.equals(storeId)
                && this.businessDate.equals(businessDate) && money(this.eligibleGross).equals(eligibleGross);
    }

    public EntryView toView() {
        return new EntryView(id, kind.name(), points, sourceType.name(), sourceId, reversesEntryId, orderId, storeId,
                businessDate, reason, occurredAt);
    }

    public Money eligibleGross() {
        return money(eligibleGross);
    }

    public Money newUnitPrice() {
        return money(newUnitPrice);
    }

    private Money money(BigDecimal amount) {
        return amount == null ? null : Money.of(amount, Currency.getInstance(currency));
    }

    public boolean isDebit() {
        return points < 0;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public EntryKind getKind() {
        return kind;
    }

    public int getPoints() {
        return points;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getProgramVersionId() {
        return programVersionId;
    }

    public UUID getRewardId() {
        return rewardId;
    }

    public UUID getVoidId() {
        return voidId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
