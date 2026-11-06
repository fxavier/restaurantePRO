package com.xavier.backend.promotions.model.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * The frozen promotions of one store, stored as jsonb when a promotion release is published (D102). It never changes
 * after publication. Amounts and percents are decimal strings ({@code "12.50"}, {@code "10"}) so no client parses
 * them as floats; amounts are in {@code currency}. Promotions are ordered by code.
 *
 * @param schemaVersion  shape version of this document (1)
 * @param discountLimits the store's manual discount limits (D106)
 * @param promotions     the ACTIVE promotions assigned to the store
 */
public record PromotionSnapshot(int schemaVersion, UUID storeId, UUID releaseId, long version, String currency,
                                DiscountLimits discountLimits, List<Promotion> promotions) {

    public static final int SCHEMA_VERSION = 1;

    /**
     * One line-level promotion (P43).
     *
     * @param kind        {@code PERCENT_OFF} (uses {@code percent}), {@code AMOUNT_OFF_UNIT} or {@code FIXED_UNIT_PRICE}
     *                    (use {@code amount})
     * @param validFrom   inclusive, {@code null} = no start
     * @param validTo     exclusive, {@code null} = no end
     * @param windows     empty = at any time of day
     * @param modes       order mode names; {@code null} = every mode
     */
    public record Promotion(UUID id, String code, String name, String kind, String percent, String amount,
                            int priority, Instant validFrom, Instant validTo, boolean membersOnly, List<String> modes,
                            Scope scope, List<Window> windows) {
    }

    /** A line matches when its item, its variant or its family is listed. */
    public record Scope(List<UUID> itemIds, List<UUID> variantIds, List<UUID> familyIds) {
    }

    /**
     * A time window in the store's zone (happy hour). {@code daysOfWeek} are ISO numbers (1 = Monday) of the day the
     * window starts on; when {@code end} is before {@code start} it crosses midnight and ends on the next day.
     */
    public record Window(List<Integer> daysOfWeek, LocalTime start, LocalTime end) {
    }
}
