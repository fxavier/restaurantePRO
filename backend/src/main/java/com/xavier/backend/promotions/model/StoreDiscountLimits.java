package com.xavier.backend.promotions.model;

import com.xavier.backend.promotions.exception.PromotionRuleException;
import com.xavier.backend.promotions.model.dto.DiscountLimits;
import com.xavier.backend.promotions.model.dto.DiscountLimitsView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A store's manual discount limits in the working copy (D106, P44): {@code 0 ≤ free ≤ max ≤ 100}. The next promotion
 * release of the store freezes them.
 */
@Entity
@Table(schema = "promotions", name = "discount_limits")
public class StoreDiscountLimits {

    /** What a store publishes before it sets its own limits (P44). */
    public static final DiscountLimits DEFAULTS = new DiscountLimits(new BigDecimal("10"), new BigDecimal("50"));

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Id
    private UUID storeId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal freeLimitPercent;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal maxPercent;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected StoreDiscountLimits() {
        // JPA
    }

    public static StoreDiscountLimits of(UUID storeId, BigDecimal freeLimitPercent, BigDecimal maxPercent, Instant now) {
        StoreDiscountLimits limits = new StoreDiscountLimits();
        limits.storeId = storeId;
        limits.change(freeLimitPercent, maxPercent, now);
        return limits;
    }

    public void change(BigDecimal freeLimitPercent, BigDecimal maxPercent, Instant now) {
        if (freeLimitPercent.signum() < 0 || freeLimitPercent.compareTo(maxPercent) > 0
                || maxPercent.compareTo(HUNDRED) > 0
                || freeLimitPercent.stripTrailingZeros().scale() > 2 || maxPercent.stripTrailingZeros().scale() > 2) {
            throw PromotionRuleException.limitsInvalid(freeLimitPercent, maxPercent);
        }
        this.freeLimitPercent = freeLimitPercent;
        this.maxPercent = maxPercent;
        this.updatedAt = now;
    }

    public DiscountLimits toLimits() {
        return new DiscountLimits(freeLimitPercent.stripTrailingZeros(), maxPercent.stripTrailingZeros());
    }

    public DiscountLimitsView toView() {
        return new DiscountLimitsView(storeId, freeLimitPercent.stripTrailingZeros(), maxPercent.stripTrailingZeros(),
                version);
    }

    public Long getVersion() {
        return version;
    }
}
